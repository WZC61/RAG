# 检索第一阶段：双路召回与 RRF

## 当前调用链

`Query → MySQL 权限范围 / FileContent INDEXED + 当前 generation → Vector 和 BM25 独立请求 → 返回前批量复查 → RRF → 简单去重 → TopK`

`HybridSearchService.retrieveWithPermission` 返回 `RetrievalResponse`；`retrieve` 为 public-only 兼容入口。原有 `searchWithPermission` / `search` 继续返回适配后的 `List<SearchResult>`。

## 范围与查询

- 权限继续采用 ES 聚合 ACL（public / allowedUserIds / allowedOrgTags）与 MySQL 有效 FileUpload 范围的交集。
- 批量读取 `FileContent`：仅 INDEXED 且 deletedAt 为空。ES filter 按 generation 分组，将对应 fileMd5 与 generation 关联，不能将两组字段独立做 terms 交集。
- 两路复用同一个 filter；Vector 为单独 KNN 请求，BM25 为单独 textContent match 请求，无 rescore、无 minScore。
- topK 必须为正，最大 20；每路候选数为 `min(100, max(30, topK * 5))`，KNN numCandidates 为候选数的 5 倍（最高 500）。两路顺序执行，故障独立。
- 返回前重新批量检查当前数据库权限、INDEXED、generation。无逐条 FileContent 查询。
- 缺少 processingGeneration 的历史 ES 文档明确排除，不把缺失值当成当前 generation。此类开发数据需要通过正确索引流程重建；本轮不自动迁移或修改这些数据。

## 结果与融合

`RetrievalResult` 保留 ES entryId、TEXT/FIGURE 类型、fileMd5、generation、文件名、页码、textContent。

TEXT 保留 chunkId / anchorText；FIGURE 保留 figureIndex / figureLabel / imagePath / bbox / caption / description / ocrText。imagePath 是稳定对象路径，不是预签名 URL。

每路从 1 开始排名，按 ES `_id` 合并，累加 `1 / (60 + rank)`。重复的同路 `_id` 只贡献一次。排序使用 RRF 分数，分数相同时按 entryId 排序；原 ES 分数不作为融合阈值。

之后去除相同业务身份；同文件、同代次、同页正文去除忽略空白后的完全重复文本，每页最多两条 TEXT。页码缺失的非 PDF 正文不做全文件两条限制。FIGURE 不受正文页数限制，不因 caption 相同而合并。完成去重后取 TopK。

## 降级与兼容

- 两路成功：HYBRID；有效空结果可以正常返回。
- Vector 失败：TEXT_ONLY，failedChannels 包含 VECTOR。
- BM25 失败：VECTOR_ONLY，failedChannels 包含 BM25。
- ES timeout / shard failure 也视为对应路失败，不接受无标识的部分响应。
- 两路失败或数据库权限/内容范围查询失败：抛 RetrievalException，不能作为成功空结果。
- 即使降级后结果为空，RetrievalResponse、搜索 API 顶层元数据和工具 data 仍保留降级标识。

SearchResult 的原字段保留，附加新模型元数据；其 score 现在表示 RRF 分数。旧 userId/orgTag 不再填入某一个上传用户，避免将共享内容解释为单一用户归属。search_knowledge 的 results 与 generate_summary 的 sources 仍为 SearchResult 列表，流式聊天编排、Prompt、前端均未修改。

## 测试与本轮边界

单元测试覆盖 RRF、两路请求、ACL、故障降级/失败、边界、代次变化、正文去重及 Figure 适配。集成测试使用独立 H2 与本地 HTTP 服务，验证真实 Repository 查询及 ES Java Client 请求序列化/响应反序列化，不触碰开发数据库和真实 API。

2026-10-06 验证：新增 56 个测试用例，调整现有 9 个权限测试；相关回归 140 个、后端全量 859 个，均无失败、错误或跳过。编译与 git diff --check 通过。统计排除了 target 中已删除临时验收入口的过期报告；本轮不是开发环境真实 ES 端到端验收。

尚未实现统一 Context Assembly、token 总预算、跨工具调用稳定引用、聊天主链调整或 Figure 图片前端展示。聊天引用仍使用旧字段与标签（例如 VECTOR_ONLY 的专用引用标签尚未适配）；工具 data 和搜索 API 已准确提供检索模式与降级元数据。当前每页两条正文是简单规则，可能减少同页大量相关证据；候选有限时去重后可能不足 TopK。数据库与 ES 范围复查是请求期检查，不提供跨系统快照。公开兼容入口仍受现有 HTTP 安全配置约束。
