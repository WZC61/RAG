package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmErrorLogSafetyTest {
    @Test void malformedStreamLogsDoNotEchoRawPayloadsOrExceptionSources() throws Exception {
        var router = new LlmProviderRouter(new AiProperties(), mock(RateLimitService.class),
                mock(UsageQuotaService.class), mock(ModelProviderConfigService.class), new ObjectMapper());
        var legacy = new DeepSeekClient("https://model.test", "", "test-model", new AiProperties(),
                mock(UsageQuotaService.class), mock(ModelProviderConfigService.class), new RagContextAssembler());
        assertSafeMalformedStream(router, "processChunk", "StreamUsageTracker");
        assertSafeMalformedStream(router, "processReActStreamChunk", "ReActStreamAccumulator");
        assertSafeMalformedStream(legacy, "processChunk", "StreamUsageTracker");
    }

    private void assertSafeMalformedStream(Object client, String method, String trackerName) throws Exception {
        var type = Class.forName(client.getClass().getName() + "$" + trackerName);
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object tracker = constructor.newInstance(null, 0);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(client.getClass());
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        try {
            String raw = "{\"choices\": review-secret-X-Amz-Signature-invalid-json}";
            ReflectionTestUtils.invokeMethod(client, method, raw, tracker, (java.util.function.Consumer<String>) ignored -> {});
            assertFalse(captured.list.isEmpty());
            assertTrue(captured.list.stream().noneMatch(e -> e.getFormattedMessage().contains("review-secret")
                    || e.getFormattedMessage().contains("X-Amz-Signature") || e.getThrowableProxy() != null));
        } finally { logger.detachAppender(captured); captured.stop(); }
    }

    @Test void providerErrorLogsOnlyStatusOrTypeNotRemoteDiagnostics() {
        var router = new LlmProviderRouter(new AiProperties(), mock(RateLimitService.class),
                mock(UsageQuotaService.class), mock(ModelProviderConfigService.class), new ObjectMapper());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LlmProviderRouter.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        String secret = "review-secret-do-not-log";
        var headers = new HttpHeaders(); headers.set("Authorization", "Bearer " + secret);
        var response = WebClientResponseException.create(401, "Unauthorized", headers,
                ("https://storage.test/?X-Amz-Signature=" + secret).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        try {
            ReflectionTestUtils.invokeMethod(router, "logProviderError", "LLM failed", response);
            ReflectionTestUtils.invokeMethod(router, "logProviderError", "LLM failed", new RuntimeException(secret));
            assertEquals(2, captured.list.size());
            assertTrue(captured.list.get(0).getFormattedMessage().contains("401"));
            assertTrue(captured.list.stream().noneMatch(e -> e.getFormattedMessage().contains(secret)
                    || e.getFormattedMessage().contains("X-Amz-Signature") || e.getThrowableProxy() != null));
        } finally { logger.detachAppender(captured); captured.stop(); }
    }
}
