package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.RateLimitService;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the public batch flow and real HTTP protocol against loopback only. */
class EmbeddingClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RateLimitService limits = mock(RateLimitService.class);
    private final UsageQuotaService quota = mock(UsageQuotaService.class);
    private final ModelProviderConfigService models = mock(ModelProviderConfigService.class);
    private final UsageQuotaService.TokenReservationBundle reservation =
            UsageQuotaService.TokenReservationBundle.of("embedding", "u1", List.of(
                    new UsageQuotaService.TokenReservation("embedding", "u1", "test-quota", "test-metric",
                            5, 100, 60, false, false)));
    private final Queue<String> responses = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;
    private EmbeddingClient client;
    private int responseStatus = 200;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            try {
                requests.add(json.readTree(exchange.getRequestBody()));
                byte[] body = responses.remove().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        when(models.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING)).thenReturn(provider(2));
        when(limits.reserveEmbeddingUploadUsage(eq("u1"), anyList())).thenReturn(reservation);
        when(limits.reserveEmbeddingQueryUsage(eq("u1"), anyList())).thenReturn(reservation);
        client = client(json);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void orderedResponsePreservesProtocolVectorsAndUsageSettlement() {
        responses.add(response("{\"index\":0,\"embedding\":[1,2]},{\"index\":1,\"embedding\":[3,4]}"));
        var result = upload(List.of("first", "second"));
        assertArrayEquals(new float[]{1, 2}, result.vectors().get(0));
        assertArrayEquals(new float[]{3, 4}, result.vectors().get(1));
        assertEquals(7, result.totalTokens());
        assertEquals("test:test-model:2", result.modelVersion());
        assertEquals(List.of("first", "second"), json.convertValue(requests.get(0).get("input"), List.class));
        assertEquals(2, requests.get(0).get("dimension").intValue());
        assertEquals("test-model", requests.get(0).get("model").textValue());
        assertEquals("float", requests.get(0).get("encoding_format").textValue());
        verify(quota).settleReservation(reservation, 7);
        verify(quota, never()).abortReservation(any(UsageQuotaService.TokenReservationBundle.class));
    }

    @Test
    void shuffledResponseRestoresOriginalInputOrder() {
        responses.add(response("{\"index\":2,\"embedding\":[5,6]},"
                + "{\"index\":0,\"embedding\":[1,2]},{\"index\":1,\"embedding\":[3,4]}"));
        var result = upload(List.of("first", "second", "third"));
        assertArrayEquals(new float[]{1, 2}, result.vectors().get(0));
        assertArrayEquals(new float[]{3, 4}, result.vectors().get(1));
        assertArrayEquals(new float[]{5, 6}, result.vectors().get(2));
    }

    @Test
    void eachBatchUsesLocalIndicesThenAppendsInDocumentOrder() {
        ReflectionTestUtils.setField(client, "batchSize", 2);
        responses.add(response("{\"index\":1,\"embedding\":[3,4]},{\"index\":0,\"embedding\":[1,2]}"));
        responses.add(response("{\"index\":0,\"embedding\":[5,6]}"));
        var result = upload(List.of("first", "second", "third"));
        assertEquals(3, result.vectors().size());
        assertArrayEquals(new float[]{1, 2}, result.vectors().get(0));
        assertArrayEquals(new float[]{3, 4}, result.vectors().get(1));
        assertArrayEquals(new float[]{5, 6}, result.vectors().get(2));
        assertEquals(14, result.totalTokens());
        assertEquals(2, requests.size());
        assertEquals(2, requests.get(0).get("input").size());
        assertEquals(1, requests.get(1).get("input").size());
        verify(quota, times(2)).settleReservation(reservation, 7);
    }

    @Test
    void validationUsesTheDimensionOfTheActualRequestDespiteConfigurationReload() {
        when(models.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING))
                .thenReturn(provider(2), provider(3));
        responses.add(response("{\"index\":0,\"embedding\":[1,2]}"));
        var result = upload(List.of("first"));
        assertArrayEquals(new float[]{1, 2}, result.vectors().get(0));
        assertEquals("test:test-model:2", result.modelVersion());
        assertEquals(2, requests.get(0).get("dimension").intValue());
    }

    @Test
    void configurationReloadCannotMixProvidersWithinOneBatchSequence() {
        ReflectionTestUtils.setField(client, "batchSize", 1);
        when(models.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING))
                .thenReturn(provider(2), provider(3));
        responses.add(response("{\"index\":0,\"embedding\":[1,2]}"));
        responses.add(response("{\"index\":0,\"embedding\":[3,4]}"));
        var result = upload(List.of("first", "second"));
        assertEquals("test:test-model:2", result.modelVersion());
        assertTrue(requests.stream().allMatch(r -> r.get("dimension").intValue() == 2));
        verify(models, times(1)).getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
    }

    @Test
    void zeroBatchSizeFailsRatherThanLoopingForever() {
        ReflectionTestUtils.setField(client, "batchSize", 0);
        assertTrue(assertThrows(RuntimeException.class, () -> upload(List.of("first")))
                .getMessage().contains("batch size"));
        assertTrue(requests.isEmpty());
        verifyNoInteractions(limits, quota);
    }

    @Test
    void httpErrorsNeverLogOrPropagateRemoteBody() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EmbeddingClient.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        String secret = "review-secret-do-not-log";
        responseStatus = 401;
        for (int i = 0; i < 4; i++) responses.add("{\"error\":\"" + secret
                + " https://storage.test/a?X-Amz-Signature=signed-secret\"}");
        try {
            var failure = assertThrows(RuntimeException.class, () -> upload(List.of("first")));
            assertFalse(failure.toString().contains(secret));
            assertNull(failure.getCause());
            assertTrue(captured.list.stream().noneMatch(e -> e.getFormattedMessage().contains(secret)
                    || e.getFormattedMessage().contains("X-Amz-Signature") || e.getThrowableProxy() != null));
        } finally { logger.detachAppender(captured); captured.stop(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[{\"index\":0,\"embedding\":[1,2]},{\"index\":1,\"embedding\":[3,4]}]"
    })
    void tooFewOrTooManyVectorsFailWithoutSettlement(String data) {
        assertInvalid("{\"data\":" + data + "}", "返回数量");
    }

    @Test
    void duplicateIndexCannotHideAnUncoveredInput() {
        responses.add(response("{\"index\":0,\"embedding\":[1,2]},{\"index\":0,\"embedding\":[3,4]}"));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> upload(List.of("first", "second")));
        assertTrue(failure.getMessage().contains("index 重复"));
        verifyAbortedWithoutSettlement();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1, 10})
    void outOfRangeIndexFails(int index) {
        assertInvalid(response("{\"index\":" + index + ",\"embedding\":[1,2]}"), "超出当前 batch");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"0\"", "true", "0.5", "2147483648", "1e20"})
    void invalidIndexTypeFails(String index) {
        assertInvalid(response("{\"index\":" + index + ",\"embedding\":[1,2]}"), "合法整数 index");
    }

    @Test
    void missingIndexFails() {
        assertInvalid(response("{\"embedding\":[1,2]}"), "合法整数 index");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[1]", "[1,2,3]"})
    void incorrectDimensionFails(String vector) {
        assertInvalid(response("{\"index\":0,\"embedding\":" + vector + "}"), "维度不匹配");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "{}", "\"not a vector\""})
    void nullEmptyOrNonArrayVectorFails(String vector) {
        assertInvalid(response("{\"index\":0,\"embedding\":" + vector + "}"), "缺失或为空");
    }

    @Test
    void missingVectorFails() {
        assertInvalid(response("{\"index\":0}"), "缺失或为空");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "\"1\"", "\"NaN\"", "\"Infinity\""})
    void nonNumericVectorElementFails(String value) {
        assertInvalid(response("{\"index\":0,\"embedding\":[" + value + ",2]}"), "非数值元素");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity"})
    void nonFiniteNumbersFailEvenIfJsonParserAcceptsThem(String value) {
        // Production's strict JSON parser also rejects these; exercise the finite check explicitly.
        client = client(JsonMapper.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build());
        assertInvalid(response("{\"index\":0,\"embedding\":[" + value + ",2]}"), "非有限数值");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e40", "-1e40", "1e309"})
    void floatOrDoubleOverflowFails(String value) {
        assertInvalid(response("{\"index\":0,\"embedding\":[" + value + ",2]}"), "非有限数值");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"data\":null}", "{\"data\":{}}", "null"})
    void missingOrInvalidDataArrayFails(String body) {
        assertInvalid(body, "data 字段");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void missingOrInvalidConfiguredDimensionAbortsBeforeHttp(Integer dimension) {
        when(models.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING)).thenReturn(provider(dimension));
        assertTrue(assertThrows(RuntimeException.class, () -> upload(List.of("first")))
                .getMessage().contains("有效的 dimension"));
        assertTrue(requests.isEmpty());
        verifyAbortedWithoutSettlement();
    }

    @Test
    void invalidLaterBatchDoesNotContinueOrSettleThatBatch() {
        ReflectionTestUtils.setField(client, "batchSize", 1);
        responses.add(response("{\"index\":0,\"embedding\":[1,2]}"));
        responses.add(response("{\"index\":1,\"embedding\":[3,4]}"));
        assertThrows(RuntimeException.class, () -> upload(List.of("first", "second", "third")));
        assertEquals(2, requests.size());
        verify(quota, times(1)).settleReservation(reservation, 7);
        verify(quota, times(1)).abortReservation(reservation);
    }

    @Test
    void queryUsageAndTokenEstimateFallbackRemainSupported() {
        responses.add("{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}");
        when(quota.estimateEmbeddingTokens(List.of("query"))).thenReturn(9);
        assertEquals(9, client.embedWithUsage(List.of("query"), "u1", EmbeddingClient.UsageType.QUERY).totalTokens());
        verify(limits).reserveEmbeddingQueryUsage("u1", List.of("query"));
        verify(limits, never()).reserveEmbeddingUploadUsage(anyString(), anyList());
        verify(quota).settleReservation(reservation, 9);
    }

    private EmbeddingClient client(ObjectMapper mapper) {
        EmbeddingClient result = new EmbeddingClient(mapper, limits, quota, models);
        ReflectionTestUtils.setField(result, "batchSize", 10);
        return result;
    }

    private ModelProviderConfigService.ActiveProviderView provider(Integer dimension) {
        return new ModelProviderConfigService.ActiveProviderView("test", "Test", "openai-compatible",
                baseUrl, "test-model", null, dimension);
    }

    private EmbeddingClient.EmbeddingUsageResult upload(List<String> texts) {
        return client.embedWithUsage(texts, "u1", EmbeddingClient.UsageType.UPLOAD);
    }

    private static String response(String items) {
        return "{\"data\":[" + items + "],\"usage\":{\"total_tokens\":7}}";
    }

    private void assertInvalid(String body, String message) {
        responses.add(body);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> upload(List.of("first")));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
        assertEquals(1, requests.size()); // Invalid 2xx results are not silently skipped or HTTP-retried.
        verifyAbortedWithoutSettlement();
    }

    private void verifyAbortedWithoutSettlement() {
        verify(quota).abortReservation(reservation);
        verify(quota, never()).settleReservation(any(UsageQuotaService.TokenReservationBundle.class), anyInt());
    }
}
