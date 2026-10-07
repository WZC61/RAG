# Figure description

本组件实现 `DocumentFigure → MinIO → Qwen3-VL → description`，已经接入
`FileProcessingConsumer` 的 PROCESS_CONTENT / PARSED 分支。组件本身只准备并保存
description；之后 VectorizationService 负责 Text/Figure Embedding 和 ES Bulk，全部成功才提交 INDEXED。
旧 UPLOAD_PROCESS / REINDEX 不调用本组件。完整链路见 `docs/multimodal-indexing.md`。

## 调用

通过 Spring 注入 `FigureDescriptionService` 后调用 `describe(fileMd5, generation)`：

- 只处理当前 `FileContent(PARSED)` 及指定 generation 的 Figures。
- 按 `pageNumber`、`figureIndex` 升序处理。已有非空 description 直接复用。
- 返回 `true` 表示当前代次的 description 已准备完成（没有 Figure 也返回 true）。
- 返回 `false` 表示内容不存在、代次已变化或状态不再允许处理。
- 图片、模型、数据库错误直接抛出，调用方可按原任务 generation 重试。

## 配置与协议

配置位于 `file.parsing.figure-description`，环境变量示例见根目录 `.env.example`。
默认模型 `qwen3-vl-flash`，默认完整 endpoint 为
`https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`。
`FIGURE_DESCRIPTION_API_KEY` 优先，未设置时读取 `DASHSCOPE_API_KEY`；缺少 Key 时
仅在实际调用时失败，启动时不发请求。根据百炼账号地域调整 endpoint 和 API Key。

使用非流式 OpenAI-compatible Chat Completions：Bearer 认证，单条 user 消息包含
`image_url` Data URI 和描述任务/图注/OCR/邻近正文，`enable_thinking=false`。
上下文默认每个字段最多 4000 字符（总预算 12000 字符均分），避免一个长字段挤掉其他参考信息；响应 description
trim 后必须非空且不超过 1000 字符（均可配置），超限失败，不静默截断模型结果。

[百炼协议参考](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)
及 [图像输入说明](https://help.aliyun.com/zh/model-studio/vision)。

## 图片与数据安全

从既有稳定 `figures/{fileMd5}/{generation}/page-{pageNumber}-figure-{figureIndex}.{ext}`
对象路径直接读取 MinIO 流，不使用预签名 URL。默认图片上限 10 MiB，流使用完立即关闭。
真实 MIME 由与 Figure 入库阶段相同的 JPEG / PNG / WebP 文件签名识别器决定，
忽略对象声明的 Content-Type。只做文件签名校验，不是完整图片解码校验。

客户端不跟随重定向，不打印请求体、Base64、API Key、远端响应或完整 URL。
HTTP/协议错误使用安全的固定异常说明。业务日志仅含内容/代次/页/图身份、模型、耗时和结果。
禁止为这些调用开启 HTTP wiretap 或请求体日志。

## 事务与恢复

协调服务使用 `NOT_SUPPORTED`，确保图片读取与模型调用不占用 MySQL 事务。
模型返回后调用独立的 `FigureDescriptionPersistenceService.save`：每张图通过
`REQUIRES_NEW` 短事务锁定 FileContent，重新检查 generation 和 PARSED 状态，重新读取
Figure 并核对 md5/generation，仅更新 description。并发已有 description 时保留先提交的值。

已提交的 description 不会因后续图片失败而回滚；重试复用已完成的图。
旧代次返回后无法覆盖新代次。没有在模型调用期间占用资格锁，因此并发请求仍可能重复付费；
模型成功后、数据库提交前崩溃也可能再次调用模型。本阶段不增加调用去重或新状态。
