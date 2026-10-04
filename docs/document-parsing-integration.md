# PDF 主链与人工 PP 联调

## 当前入口

新上传完成后的 PROCESS_CONTENT 任务：Consumer 校验 generation → 下载 task.filePath → 检查
`%PDF-` → DocumentParsingService → PP Client（JSONL Decoder / Mapper）→ Assembler → Chunker
→ 图片准备 → 短事务保存正文/Figure/PARSED → Consumer 确认 checkpoint → 原有向量化 → INDEXED。

非 PDF 保持 ParseService/Tika 与原 parsed() 更新。历史 UPLOAD_PROCESS 消息没有内容 generation，
继续兼容旧流程；REINDEX 不变。PP 未启用时，新内容 PDF 明确报配置错误，不回退 LiteParse。
Consumer 的 PDF 分支不再额外调用 parsed()，数据库持久化服务是其唯一 PARSED 提交点。

原始 PDF 用 Files.copy 流式落到 Files.createTempFile 创建的 .pdf 文件，finally 删除。
输入流由 Consumer 关闭。PP/组装/图片/提交前失败仍为 MERGED，现有 recordError + Kafka retry
接管；提交成功后的向量化失败保持 PARSED。generation 过期时返回并停止旧任务向量化。
临时文件删除失败不会覆盖已有业务异常；若它发生在成功提交后，已提交的 PARSED 不会倒退。

## 同步预估

旧 UploadController 的 estimateEmbeddingUsage 会对 PDF 完整执行 LiteParse。本轮改为只读头部：
PDF 跳过同步估算，响应不附加 estimatedEmbeddingTokens/estimatedChunkCount；非 PDF 保持原估算。
现有 ParseService PDF 估算方法及 DocumentService/REINDEX 兼容代码保留。

## 本地配置

项目根目录 `.env` 用于本机源码启动，已被 `.gitignore` 忽略。应用从运行配置的 Working
directory 读取它，因此 IDE 的工作目录应是项目根目录。系统环境变量仍然优先于 `.env`。

本机配置与 `docs/docker-compose.yaml` 对齐：MySQL 3306、Redis 6379、Kafka 9092、
MinIO API 19000 / 控制台 19001、Elasticsearch 9200（HTTP）。数据库名称使用 `paismart`。
基础设施凭据取自该 Compose 文件；JWT 使用本地生成的随机密钥。全新数据库需要管理员账号时，
可设置 ADMIN_BOOTSTRAP_ENABLED=true，填写 ADMIN_BOOTSTRAP_USERNAME 和至少 12 字符的
非弱口令 ADMIN_BOOTSTRAP_PASSWORD，管理员创建完成后关闭引导。
前端开发环境的后端地址应为 `http://localhost:8081/api/v1`。

首次配置需要填写 `PADDLEOCR_ACCESS_TOKEN` 和 `EMBEDDING_API_KEY`。
示例配置以 `PADDLEOCR_ENABLED=false` 避免空凭据造成 PP Client 初始化失败；上传 PDF 前必须
填好 PP 凭据并改为 true。验证完整 INDEXED 需要 Embedding 凭据；测试聊天时再填写
DeepSeek 凭据。`KNOWLEDGE_BOOTSTRAP_ENABLED=false` 禁止启动时自动解析内置文档。
凭据未配置时不要提交联调文件，以免无效请求进入 retry / DLT。

## 数据库结构

全新数据库使用 `docs/databases/ddl.sql`。升级已有数据库时，按现有表结构选择对应增量迁移，
不要把已经执行过的迁移重复运行。内容级状态、Outbox 和 Figure 的相关脚本为：

1. `docs/databases/processing_outbox.sql`
2. `docs/databases/file_content_migration.sql`
3. `docs/databases/document_figure_migration.sql`
4. `docs/databases/chunk_user_isolation_migration.sql`

最后一个脚本是一次性旧表迁移，仅适用于缺少 user_id 且为空的 chunk_info；执行前检查
`SELECT COUNT(*) FROM chunk_info;` 和 `SHOW CREATE TABLE chunk_info;`。
该脚本要求旧索引为 uk_file_md5_chunk_index。若表中已有数据或已有新结构，
不要直接运行此脚本。Hibernate update 不能代替旧唯一约束删除。

MinIO 控制台为 `http://localhost:19001`，需要存在 `uploads` bucket。
数据库操作不会同步清理 MinIO、Redis、Kafka 或 Elasticsearch 中的数据。

## 已完成的真实联调

PP-StructureV3 PDF 主链已完成真实联调（2026-10-04）：

- 扫描 PDF：PP 解析及 JSONL 下载成功，保存 9 条正文切片并完成 INDEXED。
- 含插图的 5 页 PDF：保存 51 条正文切片和 3 个 Figure。
- 三张 Figure 的响应 Content-Type 为 application/octet-stream，通过 JPEG 文件签名识别后
  保存为稳定 `.jpg` 路径；MinIO 对象均存在且非空，Content-Type 为 image/jpeg。
- document_figures 元数据持久化成功，MERGED → PARSED → INDEXED 链路成功；
  Outbox 最终为 SENT，processingError 为空。
- Figure description 和 Figure Embedding 不在本阶段范围内。

## 重复联调步骤

1. 完成上述数据库迁移和 bucket 准备。确认 Kafka、MinIO、MySQL 和现有向量化服务可用。
2. 在根目录 `.env` 填写 `PADDLEOCR_ACCESS_TOKEN`、`EMBEDDING_API_KEY`，并将
   `PADDLEOCR_ENABLED=true`（等价于 `paddle.pp-structure.enabled=true`）。在 IDE 运行
   `com.yizhaoqi.smartpai.SmartPaiApplication`，确认监听 8081；系统环境中已有同名配置时
   也应同步调整。凭据不要写入日志、截图或提交。
   前端目录依赖未安装时执行 `pnpm install`，然后 `pnpm dev`，访问 `http://localhost:9527`。
3. 准备一个首次上传的小 PDF，最好含正文和至少一幅插图，低于 PP Client 的 50 MiB 限制。
   内容不应已有 INDEXED 记录，否则秒传/内容复用会跳过 PP。
4. 在现有前端知识库上传入口选择文件并上传。它按原流程调用 /api/v1/upload/init、chunk、merge，
   Merge 事务写 PROCESS_CONTENT Outbox，Dispatcher 发 Kafka，Consumer 执行新 PDF 链。
5. 按 fileMd5 观察日志：PDF 跳过同步解析预估 → 接收 PROCESS_CONTENT → 开始 PP PDF 解析
   → PP 解析产物提交结束（committed=true，chunks/figures 数量）→ 原有向量化完成。
   检查 processing_outbox 为 SENT；检查 file_content generation 不变、processingError 为空，
   经过 PARSED 后最终 INDEXED。PARSED 可能很快推进，无需暂停 Consumer。
6. 检查 document_vectors 的连续 chunk_id、page_number、anchor_text；检查 document_figures
   的 generation、页码、图注、bbox、稳定 image_path。MinIO 中 merged/{fileMd5} 仍存在，
   figures/{fileMd5}/{generation}/ 下图片能打开。description 此阶段仍为 null。

成功标准：正文和所有识别出的 Figure 都可靠保存后才进入 PARSED，随后现有正文向量化完成；
PDF 消费链没有 LiteParse 完整解析，临时 PDF 在消费结束后消失。Figure description/Embedding
仍不在本轮范围。错误应保留阶段 checkpoint 和 processingError，由现有 retry / DLT 处理。

## 保留边界

不持久化 PP jobId；提交/轮询失败后的 Kafka 重试可能重新创建远端任务。MinIO 与 MySQL 不原子，
失败可能遗留稳定键图片，清理未实现。Consumer 仍使用消息中的一小时预签名 filePath；严重积压
可能遇到 URL 过期。本轮不改下载授权、Outbox、Kafka 重试、Figure 向量化或 ES/ACL。
