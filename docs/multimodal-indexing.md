# PARSED → INDEXED 多模态索引

PROCESS_CONTENT 的当前代次在 PARSED 后依次执行：

1. FigureDescriptionService 按页/图顺序准备 description；已有非空值直接复用。
2. 从 document_vectors 读取正文；从 document_figures 按 fileMd5/generation 读取图片元数据。
3. 正文调用现有 EmbeddingClient；Figure 输入为去掉空字段后的 caption、description、ocrText，
   各字段 trim 后以两个换行连接。nearbyText 只辅助 description，不重复放入 Embedding 输入。
4. 两组 Embedding 全部成功后，将 TEXT + FIGURE 合并提交现有 ES Bulk。
5. Bulk 所有 item 成功返回后，短 MySQL 事务检查仍是当前 PARSED generation，写 INDEXED 和用量。

仅正文、仅 Figure 均可成功。空白正文不索引；没有任何可索引正文或 Figure 时抛异常并保留 PARSED，
不能以零条索引结果标记 INDEXED。任何 Figure 都必须先有非空 description，不能悄悄略过失败图片。
content.actualChunkCount 对新内容任务统计 TEXT + FIGURE 的索引单元总数，actualEmbeddingTokens 为两组用量之和。

## ES 文档

共同字段：id、documentType(TEXT/FIGURE)、fileMd5、processingGeneration(long)、textContent、
vector、modelVersion、pageNumber，以及暂时沿用的 userId/orgTag/public。

- TEXT 保留 chunkId、anchorText；ID 为 `TEXT:{fileMd5}:{generation}:{chunkId}`。
- FIGURE 没有正文 chunkId；ID 为 `FIGURE:{fileMd5}:{generation}:{pageNumber}:{figureIndex}`。
  保留 figureIndex、figureLabel、imagePath、bbox、caption、description、ocrText、nearbyText。
  bbox 是数值数组（PP 无可信坐标时保留空数组），imagePath 是 MinIO 稳定对象键，不是预签名 URL。
- Java 使用 isPublic 属性，JSON 与 mapping 统一为 public，读取时兼容历史 isPublic。
  不存在 allowedUserIds/allowedOrgTags 或 ACL_CHANGED；聚合权限下一阶段处理。

## Mapping 生效

`src/main/resources/es-mappings/knowledge_base.json` 定义完整 mapping。
EsIndexInitializer 在应用启动且 elasticsearch.init.enabled=true 时：新索引 CREATE，已有索引 PUT mapping。
因此已有开发索引也会得到 documentType、processingGeneration 和 Figure 元数据字段。
该过程先 GET mapping，校验已有字段类型和向量维度，再 PUT 缺失字段；保留已有字段的 analyzer/index 设置。
它不删除索引、不迁移历史 _source、不修改历史 isPublic 字段，也不会自动重建旧 UUID 文档。
现有字段类型不兼容时 ES 会拒绝更新，应用明确报告失败；不得静默吞掉或自动删除开发数据。
开发环境不重启后端时，先 GET mapping，校验类型/维度，再将 JSON 中缺失的 properties 提交给
`PUT /knowledge_base/_mapping`；不要整份覆盖，已有 caption 等字段的 analyzer/index 参数可能不同。
只编译 Java 不保证 CommandLineRunner 已重跑；联调前需要查询实际 mapping 确认。

## 恢复与边界

- description 成功的图逐张提交，后续失败不会回滚先前结果；Kafka 重试复用已有 description。
- Embedding 失败没有 ES 写入；ES 部分成功或 INDEXED 提交失败时重试会重新 Embedding，
  使用同样业务 ID 覆盖，避免同代次重复文档。
- description/Embedding/ES 调用不在 MySQL 事务内。模型调用前后及 ES 写入前检查代次；
  最终 checkpoint 仍锁定 FileContent 检查代次。没有新的处理状态或跨存储事务。
- 代次切换与 ES 写入之间仍存在外部副作用窗口；旧代次可由 generation 字段区分，清理/查询过滤不在本轮范围。
- 沿用 Kafka 既有重试/DLT，重试期间保留 PARSED 和 processingError，DLT 后可能进入 FAILED。
- 历史 INDEXED 记录仍可能只有正文，升级代码不会自动重跑或付费补图；这里的严格完成条件适用于本版本完成的任务。
- 旧 UPLOAD_PROCESS/REINDEX 保留正文入口、LEGACY_TEXT ID、无 generation；不主动重建 Figure。
- 本轮不修改检索 DTO、图片返回、KNN/BM25 权限、聚合 ACL、共享删除生命周期。
  新 FIGURE 文档可能被既有检索召回，但目前的返回模型不会透传图片元数据；这属于下一阶段检索改造。
