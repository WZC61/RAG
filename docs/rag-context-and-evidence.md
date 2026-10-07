# RAG 上下文与引用

知识库聊天现在先由 `ChatHandler` 调用 `HybridSearchService.retrieveWithPermission(question, userId, 5)`，再进入原有 `LlmProviderRouter` 流式 ReAct 循环。首次资料获取不依赖模型选择工具。Vector、BM25、RRF、ACL 和当前 INDEXED generation 筛选沿用检索第一阶段实现。

## 上下文预算

`RagContextAssembler` 接收最终 RRF 排序的 `RetrievalResult`。每次回答创建一个 Session，主动检索和补充工具共用。

- `rag.context.max-chars` 默认 12000 字符；`max-evidence-chars` 默认 1800 字符。
- 编号、类型、文件名、页码及片段正文均计入证据预算；预留 256 字符用于状态提示。
- 按输入顺序选择，先保留排名高的证据，剩余空间不足时不加入新证据；已有证据不被替换。
- TEXT 使用正文；FIGURE 使用非空 caption、description、OCR，并给各字段合理上限。原图路径、bbox 和业务 ID 保存在引用元数据中，不加入模型语义上下文。
- 工具补充检索后替换系统消息中的单一证据块；工具消息只返回状态和编号，避免每次检索重复堆叠全文。
- 此预算约束检索证据块。系统规则、用户问题、ReAct 工具描述不计入该额度；已有历史限制继续为最近 6 条、每条约 800 字符。没有动态压缩或二次排序。

## 来源与编号

以 ES 稳定 `_id` 作为证据身份，包含类型、fileMd5、processingGeneration 和 chunk/figure 身份。旧列表入口缺失 `_id` 时使用显式 LEGACY 身份，包含类型、文件、generation、页、chunkId、figureIndex。

Session 首次接收证据时顺序分配 `[1] [2] …`；重复身份复用最早编号和文本，新身份追加。每次回答重新建立 Session；历史回答中的编号不会成为当前回答的来源。

同一 registry 生成 Prompt 和 `referenceMappings`。后者保留原有正文预览字段，并增加 entryId、documentType、processingGeneration、figureIndex、figureLabel、bbox、caption、description、ocrText 和 degraded。内部 imagePath 不再导出到新引用映射；历史中的旧路径由前端白名单过滤。映射包含本次已提供的可用来源，模型只应引用实际使用的编号，不保证所有来源都被使用。

新回答的引用格式为 `[N]`，来源区域从 referenceMappings 显示文件名、页码与 Figure 信息，避免模型补写错页码。前端同时兼容历史的 `来源#N` 格式。历史消息的编号只属于此前回答，不成为本轮来源；仅在发给模型的历史视图中移除旧助手引用标记，不改数据库答案或引用 JSON。历史回答也不构成本轮事实证据。

引用在模型开始回答/摘要流式输出前同步到当前 generation。completion、Conversation MySQL JSON、Redis 短期会话历史均使用同一映射。旧历史缺少新增字段仍可反序列化。引用详情继续检查文件访问权限，并检查回答所属用户。

## 工具与摘要

`search_knowledge`、`generate_summary`、反馈与统计工具保留；轮数、工具预算和流式停止机制保持原设计。

`generate_summary` 通过同一检索和 Context Assembly 获取资料，使用答案级 registry 分配的原编号。摘要模型只接收本次摘要所选择的证据，不重新编号。先登记引用，再调用既有摘要流（仍读取活动 LLM provider 配置）。部分摘要输出后失败时保留已有来源，并按原逻辑提示中断，不再拼接另一次模型重写。

旧 `DeepSeekClient.summarize(List<SearchResult>)` 和普通工具入口保留适配，使用相同配置的 assembler。

## 状态与边界

- 正常空结果：上下文明确资料不足；completion `retrievalStatus=EMPTY`，不会生成来源映射。
- 单路降级：可继续回答，Prompt 提示证据可能不完整；completion `DEGRADED`，来源保留 degraded 标识。
- 初始检索失败：不调用模型冒充空资料；发送 `RETRIEVAL_FAILED` error 和 failed completion，`retrievalStatus=FAILED`。
- 工具补充检索失败：作为明确失败的工具结果返回，已获得的证据保留。
- 首次检索仍使用用户当前问题；未加入 Query Rewrite 等机制，含代词的多轮追问可能需要现有工具补充检索。
- 模型遵循引用的能力通过 Prompt 引导；本阶段不增加答案重写或强制引用修复。
- Figure 图片安全接口、前端展示和真实聊天验收已完成，见 [Figure 安全读取](figure-reference-access.md) 与 [在线验收](figure-chat-acceptance.md)。

下列验证结果记录 Context Assembly 阶段的隔离服务测试；后续 Figure 阶段的真实 DeepSeek 验收单独记录，避免混淆两种验证。

## 验证结果（2026-10-06）

- 新增 36 个测试：RagContextAssemblerTest 13、RagEvidenceToolTest 4、ChatRagIntegrationTest 14、RagPromptTest 2、SummaryEvidenceTest 3。
- 相关回归 103 个通过；后端编译成功，全量 895 个通过，0 failure / error / skipped。
- 全量统计仅计入当前源码对应的测试类；target 中此前临时开发验收 probe 的两个旧报告不计入。
- 新聊天服务集成测试使用真实 assembler、工具适配、Prompt 构建与历史 JSON 逻辑，外部检索、模型、Redis 和 Repository 使用隔离替身；没有调用真实在线模型或修改开发数据库。
- 日志：target/rag-context-regression.log、target/rag-context-final-full-tests.log（构建产物，不提交）。
