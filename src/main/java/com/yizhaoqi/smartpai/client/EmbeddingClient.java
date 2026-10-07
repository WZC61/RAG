package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.RateLimitService;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 嵌入向量生成客户端
@Component
public class EmbeddingClient {

    public enum UsageType {
        UPLOAD,
        QUERY
    }

    @Value("${embedding.api.batch-size:100}")
    private int batchSize;

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingClient.class);
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final UsageQuotaService usageQuotaService;
    private final ModelProviderConfigService modelProviderConfigService;

    public EmbeddingClient(ObjectMapper objectMapper,
                           RateLimitService rateLimitService,
                           UsageQuotaService usageQuotaService,
                           ModelProviderConfigService modelProviderConfigService) {
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.usageQuotaService = usageQuotaService;
        this.modelProviderConfigService = modelProviderConfigService;
    }

    @PostConstruct
    public void init() {
        ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        logger.info("EmbeddingClient 初始化 - Provider: {}, 模型: {}, 批次大小: {}, 维度: {}, API地址: {}",
                provider.provider(), provider.model(), batchSize, provider.dimension(), provider.apiBaseUrl());
    }

    /**
     * 调用通义千问 API 生成向量
     * @param texts 输入文本列表
     * @return 对应的向量列表
     */
    public List<float[]> embed(List<String> texts) {
        return embedWithUsage(texts, "system", UsageType.UPLOAD).vectors();
    }

    public List<float[]> embed(List<String> texts, String requesterId) {
        return embedWithUsage(texts, requesterId, UsageType.UPLOAD).vectors();
    }

    public List<float[]> embed(List<String> texts, String requesterId, UsageType usageType) {
        return embedWithUsage(texts, requesterId, usageType).vectors();
    }

    public EmbeddingUsageResult embedWithUsage(List<String> texts, String requesterId, UsageType usageType) {
        try {
            if (batchSize < 1) throw new IllegalArgumentException("Embedding batch size must be positive");
            // One call must not mix providers/dimensions after a configuration reload between batches.
            ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService
                    .getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
            String normalizedRequesterId = requesterId == null || requesterId.isBlank() ? "unknown" : requesterId;
            logger.info("开始生成向量，文本数量: {}", texts.size());

            List<float[]> all = new ArrayList<>(texts.size());
            int totalTokens = 0;
            for (int start = 0; start < texts.size(); start += batchSize) {
                int end = Math.min(start + batchSize, texts.size());
                List<String> sub = texts.subList(start, end);
                UsageQuotaService.TokenReservationBundle reservation = usageType == UsageType.QUERY
                        ? rateLimitService.reserveEmbeddingQueryUsage(normalizedRequesterId, sub)
                        : rateLimitService.reserveEmbeddingUploadUsage(normalizedRequesterId, sub);
                logger.debug("调用向量 API, 批次: {}-{} (size={})", start, end - 1, sub.size());
                try {
                    if (provider.dimension() == null || provider.dimension() <= 0) {
                        throw new IllegalArgumentException("Embedding 请求必须配置有效的 dimension");
                    }
                    String response = callApiOnce(sub, provider);
                    EmbeddingApiResponse parsedResponse = parseEmbeddingResponse(response, sub, provider.dimension());
                    usageQuotaService.settleReservation(reservation, parsedResponse.totalTokens());
                    all.addAll(parsedResponse.vectors());
                    totalTokens += parsedResponse.totalTokens();
                } catch (Exception e) {
                    usageQuotaService.abortReservation(reservation);
                    throw e;
                }
            }
            logger.info("成功生成向量，总数量: {}", all.size());
            return new EmbeddingUsageResult(all, totalTokens,
                    provider.provider() + ":" + provider.model() + ":" + provider.dimension());
        } catch (WebClientResponseException e) {
            // 提供详细的API响应错误信息
            // Remote bodies/headers may echo API credentials or private input text.
            logger.error("Embedding API调用失败 - 状态码: {}", e.getStatusCode().value());
            throw new RuntimeException(String.format(
                    "向量生成失败 - API错误: HTTP %d", e.getStatusCode().value()));
        } catch (Exception e) {
            logger.error("调用向量化 API 失败 - 类型: {}", e.getClass().getSimpleName());
            // Retain local validation messages; never propagate remote response/cause chains.
            String detail = e instanceof IllegalArgumentException
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new RuntimeException("向量生成失败: " + detail);
        }
    }

    private String callApiOnce(List<String> batch, ModelProviderConfigService.ActiveProviderView provider) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", provider.model());
        requestBody.put("input", batch);
        if (provider.dimension() != null) {
            requestBody.put("dimension", provider.dimension());
        }
        requestBody.put("encoding_format", "float");

        logger.debug("发送嵌入请求 - Provider: {}, 模型: {}, 维度: {}, 批次大小: {}, 文本预览: {}",
                provider.provider(), provider.model(), provider.dimension(), batch.size(),
                batch.isEmpty() ? "空" : batch.get(0).substring(0, Math.min(50, batch.get(0).length())) + "...");

        return buildClient(provider).post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1))
                        .filter(e -> e instanceof WebClientResponseException)
                        .doBeforeRetry(signal -> logger.warn("重试API调用 - 尝试: {}, 类型: {}",
                                signal.totalRetries() + 1, signal.failure().getClass().getSimpleName())))
                .block(Duration.ofSeconds(30));
    }

    private WebClient buildClient(ModelProviderConfigService.ActiveProviderView provider) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(ModelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(provider.apiBaseUrl()))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // WebClient 的默认缓冲区大小限制（256KB）, 这里调高到 16MB
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
        }
        return builder.build();
    }

    private EmbeddingApiResponse parseEmbeddingResponse(String response, List<String> inputTexts,
                                                        int expectedDimension) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(response);
        JsonNode data = jsonNode == null ? null : jsonNode.get("data");  // 兼容模式下使用data字段
        if (data == null || !data.isArray()) {
            throw new IllegalArgumentException("API 响应格式错误: data 字段不存在或不是数组");
        }
        if (data.size() != inputTexts.size()) {
            throw new IllegalArgumentException("Embedding 返回数量与当前 batch 输入数量不一致");
        }

        // API response order is not input order. Every index must own exactly one valid vector.
        float[][] orderedVectors = new float[inputTexts.size()][];
        for (JsonNode item : data) {
            JsonNode indexNode = item.get("index");
            if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
                throw new IllegalArgumentException("Embedding 返回项缺少合法整数 index");
            }
            int index = indexNode.intValue();
            if (index < 0 || index >= inputTexts.size()) {
                throw new IllegalArgumentException("Embedding index 超出当前 batch 范围: " + index);
            }
            if (orderedVectors[index] != null) {
                throw new IllegalArgumentException("Embedding index 重复: " + index);
            }
            JsonNode embedding = item.get("embedding");
            if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
                throw new IllegalArgumentException("Embedding 向量缺失或为空: index=" + index);
            }
            if (embedding.size() != expectedDimension) {
                throw new IllegalArgumentException("Embedding 向量维度不匹配: index=" + index
                        + ", expected=" + expectedDimension + ", actual=" + embedding.size());
            }
            float[] vector = new float[expectedDimension];
            for (int i = 0; i < vector.length; i++) {
                JsonNode value = embedding.get(i);
                if (!value.isNumber()) {
                    throw new IllegalArgumentException("Embedding 向量包含非数值元素: index=" + index);
                }
                double number = value.doubleValue();
                float converted = (float) number;
                if (!Double.isFinite(number) || !Float.isFinite(converted)) {
                    throw new IllegalArgumentException("Embedding 向量包含非有限数值: index=" + index);
                }
                vector[i] = converted;
            }
            orderedVectors[index] = vector;
        }
        for (int i = 0; i < orderedVectors.length; i++) {
            if (orderedVectors[i] == null) {
                throw new IllegalArgumentException("Embedding 未覆盖输入 index: " + i);
            }
        }
        JsonNode usage = jsonNode.path("usage");
        int totalTokens = usage.path("total_tokens").asInt(usage.path("input_tokens").asInt(0));
        return new EmbeddingApiResponse(Arrays.asList(orderedVectors),
                totalTokens > 0 ? totalTokens : usageQuotaService.estimateEmbeddingTokens(inputTexts));
    }

    public String currentModelVersion() {
        ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        return provider.provider() + ":" + provider.model() + ":" + provider.dimension();
    }

    private record EmbeddingApiResponse(List<float[]> vectors, int totalTokens) {
    }

    public record EmbeddingUsageResult(List<float[]> vectors, int totalTokens, String modelVersion) {
    }
}
