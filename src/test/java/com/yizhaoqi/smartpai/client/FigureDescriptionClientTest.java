package com.yizhaoqi.smartpai.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP only; never invokes a paid model or uses application credentials. */
class FigureDescriptionClientTest {
    private static final String KEY = "test-dashscope-secret";
    private static final byte[] IMAGE = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 42, 7};
    private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"  组件A通过队列连接组件B。  \"},\"finish_reason\":\"stop\"}]}";
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService executor;
    private FigureDescriptionProperties properties;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        properties = new FigureDescriptionProperties();
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
        properties.setApiKey(KEY);
        properties.setRequestTimeout(Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private FigureDescriptionClient client() { return new FigureDescriptionClient(properties, mapper); }

    private void endpoint(int status, String body) {
        server.createContext("/chat/completions", exchange -> {
            try {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/webp"})
    void sendsSupportedImageAsExactBase64AndExtractsDescription(String mime) throws Exception {
        byte[] image = switch (mime) {
            case "image/jpeg" -> new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};
            case "image/png" -> new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
            case "image/webp" -> new byte[]{'R', 'I', 'F', 'F', 16, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', 'L', 0};
            default -> throw new IllegalArgumentException("Unexpected test MIME");
        };
        endpoint(200, OK);
        assertEquals("组件A通过队列连接组件B。", client().describe(image, mime, "图注", "文字识别", "附近正文"));
        assertEquals(1, requests.size());
        Request request = requests.get(0);
        assertEquals("POST", request.method());
        assertEquals("Bearer " + KEY, request.authorization());
        assertEquals("application/json", request.contentType());
        JsonNode root = mapper.readTree(request.body());
        assertEquals("qwen3-vl-flash", root.path("model").asText());
        assertFalse(root.path("stream").asBoolean());
        assertFalse(root.path("enable_thinking").asBoolean());
        assertEquals(1, root.path("messages").size());
        assertEquals("user", root.path("messages").get(0).path("role").asText());
        JsonNode content = root.path("messages").get(0).path("content");
        assertEquals("image_url", content.get(0).path("type").asText());
        String url = content.get(0).path("image_url").path("url").asText();
        String prefix = "data:" + mime + ";base64,";
        assertTrue(url.startsWith(prefix));
        assertArrayEquals(image, Base64.getDecoder().decode(url.substring(prefix.length())));
        String prompt = content.get(1).path("text").asText();
        assertTrue(prompt.contains("图注"));
        assertTrue(prompt.contains("文字识别"));
        assertTrue(prompt.contains("附近正文"));
        assertTrue(prompt.contains("不要虚构"));
        assertTrue(prompt.contains("纯文本"));
    }

    @Test
    void allowsMissingOptionalContextAndConfigurableModel() throws Exception {
        properties.setModel("configured-vl-model");
        endpoint(200, OK);
        assertEquals("组件A通过队列连接组件B。", client().describe(IMAGE, "image/jpeg", null, " ", null));
        JsonNode root = mapper.readTree(requests.get(0).body());
        assertEquals("configured-vl-model", root.path("model").asText());
        assertFalse(root.toString().contains("null"));
    }

    @Test
    void boundsContextBeforeSendingIt() throws Exception {
        properties.setMaxContextChars(12);
        endpoint(200, OK);
        client().describe(IMAGE, "image/jpeg", "CAPT-extra-secret", "OCRX-extra-secret", "NEAR-extra-secret");
        String body = new String(requests.get(0).body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("CAPT"));
        assertTrue(body.contains("OCRX"));
        assertTrue(body.contains("NEAR"));
        assertFalse(body.contains("extra-secret"));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 400, 401, 403, 429, 500})
    void rejectsNon2xxAndDoesNotRetryOrEchoRemoteSecrets(int status) {
        endpoint(status, "{\"error\":\"" + KEY + " data:image/jpeg;base64," + Base64.getEncoder().encodeToString(IMAGE)
                + " https://storage.test/image?X-Amz-Signature=secret"
                + " https://bos.test/image?authorization=bce-auth-v1/secret\"}");
        IOException error = assertThrows(IOException.class, () -> client().describe(IMAGE, "image/jpeg", null, null, null));
        assertEquals("Figure description HTTP status " + status, error.getMessage());
        assertNull(error.getCause());
        assertEquals(1, requests.size());
        assertFalse(error.toString().contains(KEY));
        assertFalse(error.toString().contains("base64"));
        assertFalse(error.toString().contains("X-Amz-Signature"));
        assertFalse(error.toString().contains("authorization=bce-auth-v1"));
    }

    @Test
    void redirectDoesNotForwardCredentials() {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/target", exchange -> {
            redirected.incrementAndGet();
            exchange.close();
        });
        server.createContext("/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Location", "/target");
                exchange.sendResponseHeaders(307, -1);
            } finally { exchange.close(); }
        });
        IOException error = assertThrows(IOException.class, () -> client().describe(IMAGE, "image/jpeg", null, null, null));
        assertEquals("Figure description HTTP status 307", error.getMessage());
        assertEquals(0, redirected.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{}", "[]", "{\"choices\":null}", "{\"choices\":{}}", "{\"choices\":[]}",
            "{\"choices\":[null]}", "{\"choices\":[{}]}", "{\"choices\":[{\"message\":{}}]}",
            "{\"choices\":[{\"message\":{\"content\":null}}]}", "{\"choices\":[{\"message\":{\"content\":[]}}]}",
            "{\"choices\":[{\"message\":{\"content\":\"\"}}]}", "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}",
            "{\"choices\":[{\"message\":{\"content\":\"截断文本\"},\"finish_reason\":\"length\"}]}",
            "{\"choices\":[{\"message\":{\"content\":\"正常文本\"}}]} {}"})
    void rejectsMalformedMissingEmptyOrTruncatedResponse(String body) {
        endpoint(200, body);
        IOException error = assertThrows(IOException.class, () -> client().describe(IMAGE, "image/jpeg", null, null, null));
        assertNull(error.getCause());
        assertTrue(error.getMessage().startsWith("Figure description response"));
    }

    @Test
    void usesAValidChoiceAfterAnInvalidChoice() throws Exception {
        endpoint(200, "{\"choices\":[{\"message\":{}},{\"message\":{\"content\":\"有效描述\"}}]}");
        assertEquals("有效描述", client().describe(IMAGE, "image/jpeg", null, null, null));
    }

    @Test
    void rejectsOverlongDescriptionWithoutTruncating() {
        properties.setMaxDescriptionLength(3);
        endpoint(200, "{\"choices\":[{\"message\":{\"content\":\"超过三个字\"}}]}");
        assertThrows(IOException.class, () -> client().describe(IMAGE, "image/jpeg", null, null, null));
    }

    @Test
    void acceptsDescriptionAtConfiguredLengthLimitAfterTrim() throws Exception {
        properties.setMaxDescriptionLength(4);
        endpoint(200, "{\"choices\":[{\"message\":{\"content\":\"  四字描述  \"}}]}");
        assertEquals("四字描述", client().describe(IMAGE, "image/jpeg", null, null, null));
    }

    @Test
    void boundsResponseBytes() {
        properties.setMaxResponseBytes(128);
        endpoint(200, "{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(2048) + "\"}}]}");
        IOException error = assertThrows(IOException.class, () -> client().describe(IMAGE, "image/jpeg", null, null, null));
        assertEquals("Figure description response exceeds size limit", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void timeoutIsBoundedAndDoesNotExposeRawHttpFailure() {
        properties.setRequestTimeout(Duration.ofMillis(100));
        server.createContext("/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(500);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        IOException error = assertThrows(IOException.class, () -> client().describe(IMAGE, "image/jpeg", null, null, null));
        assertEquals("Figure description HTTP request failed or timed out", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void missingKeyFailsAtInvocationAndNotConstruction() {
        properties.setApiKey("");
        FigureDescriptionClient client = assertDoesNotThrow(this::client);
        IOException error = assertThrows(IOException.class, () -> client.describe(IMAGE, "image/jpeg", null, null, null));
        assertTrue(error.getMessage().contains("API key"));
        assertTrue(requests.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/image", "https://secret@example.test/chat", "https://example.test/chat?apiKey=secret", "https://example.test/chat#secret", "not-a-url"})
    void rejectsUnsafeEndpointWithoutEchoingIt(String endpoint) {
        properties.setEndpoint(endpoint);
        IOException error = assertThrows(IOException.class, () -> client().describe(IMAGE, "image/jpeg", null, null, null));
        assertFalse(error.getMessage().contains(endpoint));
        assertFalse(error.getMessage().contains("secret"));
        assertNull(error.getCause());
    }

    @Test
    void rejectsNullEmptyOversizedAndUnsupportedImageWithoutCallingHttp() {
        FigureDescriptionClient client = client();
        assertThrows(IOException.class, () -> client.describe(null, "image/jpeg", null, null, null));
        assertThrows(IOException.class, () -> client.describe(new byte[0], "image/jpeg", null, null, null));
        properties.setMaxImageBytes(4);
        assertThrows(IOException.class, () -> client.describe(IMAGE, "image/jpeg", null, null, null));
        properties.setMaxImageBytes(100);
        assertThrows(IOException.class, () -> client.describe(IMAGE, "image/gif", null, null, null));
        assertTrue(requests.isEmpty());
    }

    @Test
    void debugLogsAndSafeExceptionsNeverContainKeyBase64OrRemoteUrl() {
        String imageBase64 = Base64.getEncoder().encodeToString(IMAGE);
        endpoint(200, "{\"error\":\"" + KEY + " data:image/jpeg;base64," + imageBase64
                + " https://storage.test/image?X-Amz-Signature=secret"
                + " https://bos.test/image?authorization=bce-auth-v1/secret\"}");
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        logger.setLevel(Level.DEBUG);
        try {
            IOException error = assertThrows(IOException.class, () -> client().describe(IMAGE, "image/jpeg", null, null, null));
            String logs = captured.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + b)
                    + error;
            assertFalse(logs.contains(KEY));
            assertFalse(logs.contains(imageBase64));
            assertFalse(logs.contains("data:image"));
            assertFalse(logs.contains("X-Amz-Signature"));
            assertFalse(logs.contains("authorization=bce-auth-v1"));
            assertFalse(logs.contains("https://storage.test"));
            assertFalse(logs.contains("https://bos.test"));
            assertNull(error.getCause());
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    private record Request(String method, String authorization, String contentType, byte[] body) {}
}
