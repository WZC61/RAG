package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.DocStats;
import co.elastic.clients.elasticsearch._types.StoreStats;
import co.elastic.clients.elasticsearch.indices.IndicesStatsResponse;
import co.elastic.clients.elasticsearch.indices.stats.IndicesStats;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.entity.RetrievalResponse;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class AgentToolRegistry {

    private static final String KNOWLEDGE_INDEX = "knowledge_base";
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_SEARCH_DOCS = HybridSearchService.MAX_TOP_K;

    private final HybridSearchService hybridSearchService;
    private final DeepSeekClient deepSeekClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ElasticsearchClient elasticsearchClient;
    private final FileUploadRepository fileUploadRepository;
    private final RagContextAssembler contextAssembler;
    private final List<AgentTool> tools;
    private final Map<String, ToolHandler> handlers;

    public AgentToolRegistry(HybridSearchService hybridSearchService,
                             DeepSeekClient deepSeekClient,
                             StringRedisTemplate stringRedisTemplate,
                             ElasticsearchClient elasticsearchClient,
                             FileUploadRepository fileUploadRepository,
                             RagContextAssembler contextAssembler) {
        this.hybridSearchService = hybridSearchService;
        this.deepSeekClient = deepSeekClient;
        this.stringRedisTemplate = stringRedisTemplate;
        this.elasticsearchClient = elasticsearchClient;
        this.fileUploadRepository = fileUploadRepository;
        this.contextAssembler = contextAssembler;
        this.tools = List.of(
                searchKnowledgeTool(),
                generateSummaryTool(),
                submitFeedbackTool(),
                knowledgeStatsTool()
        );
        this.handlers = Map.of(
                "search_knowledge", this::executeSearchKnowledge,
                "generate_summary", this::executeGenerateSummary,
                "submit_feedback", this::executeSubmitFeedback,
                "knowledge_stats", this::executeKnowledgeStats
        );
    }

    public List<AgentTool> getTools() {
        return tools;
    }

    public Optional<AgentTool> getTool(String name) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst();
    }

    public ToolExecutionResult executeTool(String name, Map<String, Object> arguments, String userId) {
        return executeTool(name, arguments, userId, null);
    }

    public ToolExecutionResult executeTool(String name,
                                           Map<String, Object> arguments,
                                           String userId,
                                           Consumer<String> onChunk) {
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }
        return handler.execute(arguments == null ? Collections.emptyMap() : arguments, userId, onChunk);
    }

    private ToolExecutionResult executeSearchKnowledge(Map<String, Object> arguments,
                                                       String userId,
                                                       Consumer<String> onChunk) {
        return executeEvidenceTool("search_knowledge", arguments, userId, onChunk, contextAssembler.newSession(), null, false);
    }

    private ToolExecutionResult executeGenerateSummary(Map<String, Object> arguments,
                                                       String userId, Consumer<String> onChunk) {
        return executeEvidenceTool("generate_summary", arguments, userId, onChunk, contextAssembler.newSession(), null, false);
    }

    /** Answer-scoped registry shared by proactive retrieval and all subsequent tools. */
    public ToolExecutionResult executeToolWithEvidence(String name, Map<String,Object> arguments, String userId,
            Consumer<String> onChunk, RagContextAssembler.Session session,
            Consumer<RagContextAssembler.Context> onEvidence) {
        if ("search_knowledge".equals(name) || "generate_summary".equals(name)) {
            return executeEvidenceTool(name, arguments == null ? Map.of() : arguments, userId, onChunk,
                    session, onEvidence, true);
        }
        return executeTool(name, arguments, userId, onChunk);
    }

    private ToolExecutionResult executeEvidenceTool(String name, Map<String,Object> arguments, String userId,
            Consumer<String> onChunk, RagContextAssembler.Session session,
            Consumer<RagContextAssembler.Context> onEvidence, boolean sharedContext) {
        requireUserId(userId);
        boolean summary = "generate_summary".equals(name);
        String query = getRequiredString(arguments, summary ? "topic" : "query");
        int topK = getInt(arguments, summary ? "maxDocs" : "topK", DEFAULT_TOP_K, 1, MAX_SEARCH_DOCS);
        RetrievalResponse retrieval = hybridSearchService.retrieveWithPermission(query, userId, topK);
        RagContextAssembler.Context context = session.add(retrieval, query);
        // Publish references before the first summary chunk, including when its stream later fails.
        if (onEvidence != null) onEvidence.accept(session.snapshot());
        List<SearchResult> results = context.evidence().stream()
                .map(e -> SearchResult.fromRetrieval(e.source(), retrieval)).toList();
        Map<String,Object> data = new LinkedHashMap<>();
        data.put(summary ? "topic" : "query", query);
        data.put(summary ? "maxDocs" : "topK", topK);
        data.put(summary ? "sources" : "results", results);
        data.put("evidenceNumbers", context.evidence().stream().map(RagContextAssembler.Evidence::number).toList());
        data.put("sourceCount", results.size());
        addRetrievalMetadata(data, retrieval);
        String content;
        if (summary) {
            content = deepSeekClient.summarizeContext(userId, query, context, onChunk);
        } else {
            content = sharedContext
                    ? "检索状态：" + (context.degraded() ? "降级" : "正常")
                        + "；已加入当前上下文的来源编号：" + data.get("evidenceNumbers")
                        + "。证据正文见系统上下文；资料不足时应明确说明。"
                    : context.text();
        }
        return new ToolExecutionResult(name, true, content, data, summary && onChunk != null);
    }

    private void addRetrievalMetadata(Map<String, Object> data, RetrievalResponse retrieval) {
        data.put("retrievalMode", retrieval.getRetrievalMode());
        data.put("degraded", retrieval.isDegraded());
        data.put("failedChannels", retrieval.failedChannels());
    }

    private ToolExecutionResult executeSubmitFeedback(Map<String, Object> arguments,
                                                      String userId,
                                                      Consumer<String> onChunk) {
        requireUserId(userId);
        String rating = getRequiredString(arguments, "rating").toLowerCase(Locale.ROOT);
        if (!"good".equals(rating) && !"bad".equals(rating)) {
            throw new IllegalArgumentException("rating 只允许 good 或 bad");
        }
        String reason = getOptionalString(arguments, "reason");
        String key = "feedback:" + userId;
        String field = String.valueOf(System.currentTimeMillis());
        String value = reason == null || reason.isBlank()
                ? "rating=" + rating
                : "rating=" + rating + "; reason=" + reason;
        stringRedisTemplate.opsForHash().put(key, field, value);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("field", field);
        data.put("rating", rating);
        data.put("reason", reason);
        return new ToolExecutionResult("submit_feedback", true, "已记录用户反馈: " + value, data);
    }

    private ToolExecutionResult executeKnowledgeStats(Map<String, Object> arguments,
                                                      String userId,
                                                      Consumer<String> onChunk) {
        try {
            IndicesStatsResponse statsResponse = elasticsearchClient.indices().stats(s -> s.index(KNOWLEDGE_INDEX));
            IndicesStats indexStats = statsResponse.indices().get(KNOWLEDGE_INDEX);
            DocStats docStats = indexStats != null && indexStats.total() != null ? indexStats.total().docs() : null;
            StoreStats storeStats = indexStats != null && indexStats.total() != null ? indexStats.total().store() : null;

            long documentCount = fileUploadRepository.count();
            long fragmentCount = docStats != null ? docStats.count() : 0L;
            Long deletedFragmentCount = docStats != null ? docStats.deleted() : null;
            Long storeSizeInBytes = storeStats != null ? storeStats.sizeInBytes() : null;
            LocalDateTime latestUpdatedAt = fileUploadRepository.findFirstByOrderByMergedAtDesc()
                    .map(this::resolveLatestUpdatedAt)
                    .orElse(null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("index", KNOWLEDGE_INDEX);
            data.put("documentCount", documentCount);
            data.put("fragmentCount", fragmentCount);
            data.put("deletedFragmentCount", deletedFragmentCount);
            data.put("storeSizeInBytes", storeSizeInBytes);
            data.put("latestUpdatedAt", latestUpdatedAt);

            return new ToolExecutionResult("knowledge_stats", true, formatKnowledgeStats(data), data);
        } catch (Exception e) {
            throw new RuntimeException("获取知识库统计信息失败", e);
        }
    }

    private AgentTool searchKnowledgeTool() {
        return new AgentTool(
                "search_knowledge",
                "补充检索知识库中的 TEXT / FIGURE 证据。服务端已完成初始检索，仅在当前上下文不足、需要补充具体资料时调用。来源编号与当前回答共用，重复来源不会重新编号。",
                objectSchema(Map.of(
                        "query", stringSchema("用于知识库检索的查询语句。应保留用户原话中的核心实体、缩写和限定词，可包含原始问句和必要的等价改写；不要替换成固定关键词。"),
                        "topK", integerSchema("返回的片段数量，默认 5。")
                ), List.of("query"))
        );
    }

    private AgentTool generateSummaryTool() {
        return new AgentTool(
                "generate_summary",
                "对指定主题的知识库文档生成结构化摘要。适合用户要求整理、总结、归纳、提炼知识库内容时调用；本工具内部会二次调用大模型完成摘要，外层 ReAct 循环只应接收结果，不要把内部摘要过程当作新的工具计划。",
                objectSchema(Map.of(
                        "topic", stringSchema("需要从知识库中整理和总结的主题。"),
                        "maxDocs", integerSchema("用于生成摘要的最多相关片段数量，默认 5。")
                ), List.of("topic"))
        );
    }

    private AgentTool submitFeedbackTool() {
        Map<String, Object> ratingSchema = stringSchema("用户对当前回答的评价，只能是 good 或 bad。");
        ratingSchema.put("enum", List.of("good", "bad"));
        return new AgentTool(
                "submit_feedback",
                "当用户明确表达对回答满意、不满意、点赞、点踩、纠错或要求记录反馈时调用，用于记录反馈以优化后续回答质量；不要在没有明确评价意图时推断调用。",
                objectSchema(Map.of(
                        "rating", ratingSchema,
                        "reason", stringSchema("用户给出的满意或不满意原因，可为空。")
                ), List.of("rating"))
        );
    }

    private AgentTool knowledgeStatsTool() {
        return new AgentTool(
                "knowledge_stats",
                "返回当前知识库的统计信息，包括 MySQL 文档总数、Elasticsearch 片段总数、索引存储量和最近更新时间。仅当用户询问知识库规模、文档数量、片段数量、更新时间或索引状态时调用。",
                objectSchema(Collections.emptyMap(), Collections.emptyList())
        );
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> stringSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> integerSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    private String formatKnowledgeStats(Map<String, Object> data) {
        return "知识库统计："
                + "\n- MySQL 文档总数：" + data.get("documentCount")
                + "\n- Elasticsearch 片段总数：" + data.get("fragmentCount")
                + "\n- ES 已删除片段数：" + nullToDash(data.get("deletedFragmentCount"))
                + "\n- ES 存储大小(bytes)：" + nullToDash(data.get("storeSizeInBytes"))
                + "\n- 最近更新时间：" + nullToDash(data.get("latestUpdatedAt"));
    }

    private LocalDateTime resolveLatestUpdatedAt(FileUpload fileUpload) {
        if (fileUpload.getMergedAt() != null) {
            return fileUpload.getMergedAt();
        }
        return fileUpload.getCreatedAt();
    }

    private String getRequiredString(Map<String, Object> arguments, String name) {
        String value = getOptionalString(arguments, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private String getOptionalString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private int getInt(Map<String, Object> arguments, String name, int defaultValue, int min, int max) {
        Object raw = arguments.get(name);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return defaultValue;
        }

        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else {
            value = Integer.parseInt(String.valueOf(raw));
        }
        return Math.max(min, Math.min(max, value));
    }

    private String nullToDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("工具调用缺少 userId，无法执行带权限的知识库或反馈操作");
        }
    }

    @FunctionalInterface
    private interface ToolHandler {
        ToolExecutionResult execute(Map<String, Object> arguments, String userId, Consumer<String> onChunk);
    }

    public record AgentTool(
            String name,
            String description,
            Map<String, Object> parameters
    ) {
    }

    public record ToolExecutionResult(
            String toolName,
            boolean success,
            String content,
            Map<String, Object> data,
            boolean streamedToUser
    ) {
        public ToolExecutionResult(String toolName,
                                   boolean success,
                                   String content,
                                   Map<String, Object> data) {
            this(toolName, success, content, data, false);
        }
    }
}
