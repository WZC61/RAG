package com.yizhaoqi.smartpai.parsing.pp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.parsing.PpStructureResultMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.yizhaoqi.smartpai.parsing.pp.PpStructureApiException.Stage.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP against loopback only; never contacts Paddle or uses application credentials. */
class PpStructureApiClientTest {
    private static final String TOKEN = "mock-paddle-secret";
    private static final String JOB = "ocrjob-test";
    private static final String JOBS = "/api/v2/ocr/jobs";
    private final ObjectMapper json = new ObjectMapper();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    @TempDir Path directory;
    private Path pdf;
    private HttpServer server;
    private ExecutorService executor;
    private String base;
    private PpStructureApiProperties properties;

    @BeforeEach
    void setup() throws Exception {
        pdf = Files.writeString(directory.resolve("document.pdf"), "%PDF-1.7\nmock binary document\n%%EOF");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        properties = new PpStructureApiProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(base);
        properties.setAccessToken(TOKEN);
        properties.setInitialPollInterval(Duration.ofMillis(5));
        properties.setMaxPollInterval(Duration.ofMillis(15));
        properties.setPollTimeout(Duration.ofSeconds(3));
        properties.setRequestTimeout(Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private PpStructureApiClient client() {
        return new PpStructureApiClient(properties, json, new PpStructureResultDecoder(json), new PpStructureResultMapper(json));
    }

    @Test
    void submitsFileAsMultipartWithExactProtocolAndBearer() throws Exception {
        endpoint(JOBS, 200, "{\"code\":0,\"data\":{\"jobId\":\"" + JOB + "\"}}");
        assertEquals(JOB, client().submitPdf(pdf).jobId());
        assertEquals(1, requests.size());
        Request request = requests.get(0);
        assertEquals("POST", request.method);
        assertEquals("Bearer " + TOKEN, request.auth);
        assertTrue(request.contentType.startsWith("multipart/form-data;boundary="));
        assertTrue(request.body.contains("name=\"model\""));
        assertTrue(request.body.contains("PP-StructureV3"));
        assertTrue(request.body.contains("name=\"optionalPayload\""));
        assertTrue(request.body.contains("{\"useDocOrientationClassify\":false,\"useDocUnwarping\":false,\"returnMarkdownImages\":true}"));
        assertTrue(request.body.contains("name=\"file\"; filename=\"document.pdf\""));
        assertTrue(request.body.contains(Files.readString(pdf)));
        assertTrue(request.body.contains("Content-Type: application/pdf"));
    }

    @Test
    void businessErrorPreservesMetadataAndRedactsToken() {
        endpoint(JOBS, 200, "{\"code\":10007,\"traceId\":\"trace-1\",\"msg\":\"bad " + TOKEN + "\",\"data\":{}}");
        var error = assertThrows(PpStructureApiException.class, () -> client().submitPdf(pdf));
        assertEquals(SUBMIT, error.getStage());
        assertEquals(200, error.getHttpStatus());
        assertEquals(10007, error.getBusinessCode());
        assertEquals("trace-1", error.getTraceId());
        assertFalse(error.toString().contains(TOKEN));
        assertNull(error.getCause());
        assertEquals(1, requests.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 429, 500, 503})
    void httpErrorsAreNotRetriedEvenIfBusinessCodeIsZero(int status) {
        endpoint(JOBS, status, "{\"code\":0,\"data\":{\"jobId\":\"x\"}}");
        var error = assertThrows(PpStructureApiException.class, () -> client().submitPdf(pdf));
        assertEquals(status, error.getHttpStatus());
        assertEquals(1, requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"code\":0,\"data\":{}}", "{\"code\":0,\"data\":{\"jobId\":\" \"}}",
            "{\"data\":{\"jobId\":\"x\"}}", "{\"code\":0}", "not json", "null"})
    void malformedSubmitResponseFails(String body) {
        endpoint(JOBS, 200, body);
        assertEquals(SUBMIT, assertThrows(PpStructureApiException.class, () -> client().submitPdf(pdf)).getStage());
    }

    @Test
    void statusSupportsNumericAndStringProgress() {
        endpoint(JOBS, 200, "{\"code\":0,\"data\":{\"state\":\"running\",\"extractProgress\":{\"totalPages\":\"10\",\"extractedPages\":2}}}");
        var status = client().getStatus(JOB);
        assertEquals(10, status.totalPages());
        assertEquals(2, status.extractedPages());
        assertEquals(JOBS + "/" + JOB, requests.get(0).path);
        assertEquals("Bearer " + TOKEN, requests.get(0).auth);
    }

    @Test
    void failedStatusContainsJobAndReason() {
        endpoint(JOBS, 200, "{\"code\":0,\"data\":{\"state\":\"failed\",\"errorMsg\":\"unsupported content\"}}");
        var error = assertThrows(PpStructureApiException.class, () -> client().awaitResult(JOB));
        assertEquals(STATUS, error.getStage());
        assertEquals(JOB, error.getJobId());
        assertTrue(error.getMessage().contains("unsupported content"));
        assertEquals(1, requests.size());
    }

    @Test
    void failedBusinessCodeOnHttp200RetainsFailureDetails() {
        endpoint(JOBS, 200, "{\"code\":11003,\"traceId\":\"trace\",\"data\":{\"state\":\"failed\",\"errorMsg\":\"parse failed\"}}");
        var error = assertThrows(PpStructureApiException.class, () -> client().getStatus(JOB));
        assertEquals(11003, error.getBusinessCode());
        assertEquals(JOB, error.getJobId());
        assertTrue(error.getMessage().contains("parse failed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"state\":\"future\"}", "{}", "{\"state\":\"done\"}",
            "{\"state\":\"running\",\"jobId\":\"other\"}"})
    void invalidStatusIsProtocolFailure(String data) {
        endpoint(JOBS, 200, "{\"code\":0,\"data\":" + data + "}");
        assertEquals(STATUS, assertThrows(PpStructureApiException.class, () -> client().getStatus(JOB)).getStage());
    }

    @Test
    void fullFlowPollsThenMapsRealFixtureWithoutDownloadingFigures() throws Exception {
        var pages = json.readTree(getClass().getResourceAsStream("/pp-structure/pp-structure-sample.json"));
        var wrapper = json.createObjectNode();
        wrapper.putObject("result").set("layoutParsingResults", pages);
        endpoint("/result", 200, json.writeValueAsString(wrapper));
        server.createContext(JOBS, exchange -> {
            record(exchange);
            int count = (int) requests.stream().filter(r -> r.path.startsWith(JOBS)).count();
            String body = switch (count) {
                case 1 -> "{\"code\":0,\"data\":{\"jobId\":\"" + JOB + "\"}}";
                case 2 -> state("pending");
                case 3 -> state("running");
                default -> done(base + "/result");
            };
            respond(exchange, 200, body, false);
        });
        var result = client().parsePdf(pdf);
        var expected = new PpStructureResultMapper(json).map(pages);
        assertEquals(expected, result);
        assertFalse(result.pages().isEmpty());
        assertFalse(result.getAllTextBlocks().isEmpty());
        assertEquals(4, result.getAllFigures().size());
        assertTrue(result.getAllFigures().stream().anyMatch(f -> f.sourceImageUrl() != null));
        assertEquals(5, requests.size());
        assertNull(requests.get(4).auth);
        assertEquals("/result", requests.get(4).path);
    }

    @Test
    void crossOriginResourceDoesNotReceiveBearer() throws Exception {
        HttpServer resource = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        resource.setExecutor(executor);
        resource.createContext("/jsonl", e -> { record(e); respond(e, 200, minimalJsonl(), false); });
        resource.start();
        try {
            endpoint(JOBS, 200, done("http://127.0.0.1:" + resource.getAddress().getPort() + "/jsonl"));
            assertFalse(client().awaitResult(JOB).pages().isEmpty());
            assertNull(requests.get(1).auth);
        } finally { resource.stop(0); }
    }

    @Test
    void signedResultUrlPreservesRawPathAndQueryAndCarriesNoBearer() {
        String path = "/result%20document.json";
        String query = "authorization=bce-auth-v1%2Fmock-key%2F2026-10-04T07%3A38%3A07Z"
                + "%2F604800%2F%2Fmock-signature&marker=a%2Bb%3Dc";
        endpoint(JOBS, 200, done(base + path + "?" + query));
        server.createContext("/result document.json", exchange -> {
            record(exchange);
            assertEquals(path, exchange.getRequestURI().getRawPath());
            assertEquals(query, exchange.getRequestURI().getRawQuery());
            assertNull(exchange.getRequestHeaders().getFirst("Content-Length"));
            assertNull(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            respond(exchange, 200, minimalJsonl(), false);
        });
        assertEquals(1, client().awaitResult(JOB).pages().size());
        assertNull(requests.get(1).auth);
    }

    @Test
    void repeatedResourceGetsOnPooledConnectionsStayBodyless() {
        endpoint(JOBS, 200, done(base + "/result"));
        server.createContext("/result", exchange -> {
            record(exchange);
            assertNull(exchange.getRequestHeaders().getFirst("Content-Length"));
            assertNull(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            respond(exchange, 200, minimalJsonl(), false);
        });
        var client = client();
        for (int i = 0; i < 3; i++) assertEquals(1, client.awaitResult(JOB).pages().size());
        assertEquals(6, requests.size());
        for (int i = 1; i < requests.size(); i += 2) assertNull(requests.get(i).auth);
    }

    @Test
    void pollDeadlineClampsSleep() {
        endpoint(JOBS, 200, state("pending"));
        properties.setPollTimeout(Duration.ofMillis(200));
        properties.setInitialPollInterval(Duration.ofSeconds(2));
        properties.setMaxPollInterval(Duration.ofSeconds(2));
        var client = client();
        var error = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThrows(PpStructureApiException.class, () -> client.awaitResult(JOB)));
        assertEquals(POLL, error.getStage());
        assertEquals(JOB, error.getJobId());
    }

    @Test
    void pollDeadlineAlsoBoundsSlowStatusRequest() {
        delayedEndpoint(JOBS, state("running"), 1500);
        properties.setPollTimeout(Duration.ofMillis(200));
        var client = client();
        var error = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertThrows(PpStructureApiException.class, () -> client.awaitResult(JOB)));
        assertEquals(POLL, error.getStage());
        assertEquals(JOB, error.getJobId());
    }

    @Test
    void pollDeadlineAlsoBoundsSlowResourceDownload() {
        endpoint(JOBS, 200, done(base + "/result"));
        delayedEndpoint("/result", minimalJsonl(), 1500);
        properties.setPollTimeout(Duration.ofMillis(300));
        var client = client();
        var error = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertThrows(PpStructureApiException.class, () -> client.awaitResult(JOB)));
        assertEquals(POLL, error.getStage());
    }

    @Test
    void submitTimeoutDoesNotResubmit() {
        delayedEndpoint(JOBS, "{\"code\":0,\"data\":{\"jobId\":\"late\"}}", 1000);
        properties.setRequestTimeout(Duration.ofMillis(150));
        var error = assertThrows(PpStructureApiException.class, () -> client().submitPdf(pdf));
        assertEquals(SUBMIT, error.getStage());
        assertTrue(error.getMessage().contains("timed out"));
        assertEquals(1, requests.size());
    }

    @Test
    void interruptDuringSleepRestoresFlagAndStopsPolling() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        server.createContext(JOBS, e -> {
            record(e); respond(e, 200, state("pending"), false); received.countDown();
        });
        properties.setInitialPollInterval(Duration.ofSeconds(2));
        properties.setMaxPollInterval(Duration.ofSeconds(2));
        var client = client();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        Thread worker = new Thread(() -> {
            try { client.awaitResult(JOB); } catch (Throwable e) { failure.set(e); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        worker.start();
        try {
            assertTrue(received.await(2, TimeUnit.SECONDS));
            // Wait for either the HTTP block or polling sleep; both must preserve interruption.
            worker.interrupt();
            worker.join(1000);
            assertFalse(worker.isAlive());
            assertInstanceOf(PpStructureApiException.class, failure.get());
            assertEquals(JOB, ((PpStructureApiException) failure.get()).getJobId());
            assertTrue(interrupted.get());
        } finally { worker.interrupt(); worker.join(1000); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualDownloadByteLimitIncludesChunkedResponses(boolean chunked) {
        endpoint(JOBS, 200, done(base + "/result"));
        server.createContext("/result", e -> { record(e); respond(e, 200, minimalJsonl(), chunked); });
        properties.setResultMaxBytes(10);
        var error = assertThrows(PpStructureApiException.class, () -> client().awaitResult(JOB));
        assertEquals(RESULT_DOWNLOAD, error.getStage());
        assertTrue(error.getMessage().contains("byte limit"));
    }

    @Test
    void exactDownloadLimitIsAccepted() {
        endpoint(JOBS, 200, done(base + "/result"));
        endpoint("/result", 200, minimalJsonl());
        properties.setResultMaxBytes(minimalJsonl().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(1, client().awaitResult(JOB).pages().size());
    }

    @Test
    void downloadHttpFailureRetainsStageAndJob() {
        endpoint(JOBS, 200, done(base + "/result"));
        endpoint("/result", 403, "expired " + TOKEN);
        var error = assertThrows(PpStructureApiException.class, () -> client().awaitResult(JOB));
        assertEquals(RESULT_DOWNLOAD, error.getStage());
        assertEquals(403, error.getHttpStatus());
        assertEquals(JOB, error.getJobId());
        assertTrue(error.getMessage().contains("HTTP 403"));
        assertFalse(error.toString().contains(TOKEN));
    }

    @Test
    void bosFailureRetainsSafeCodeAndRequestIdWithoutRawBody() {
        endpoint(JOBS, 200, done(base + "/result"));
        endpoint("/result", 403, "{\"code\":\"SignatureDoesNotMatch\","
                + "\"message\":\"private signed URL and " + TOKEN + "\","
                + "\"requestId\":\"c6b7d182-723c-4027-9e88-e43b38da9fea\"}");
        var error = assertThrows(PpStructureApiException.class, () -> client().awaitResult(JOB));
        assertEquals(RESULT_DOWNLOAD, error.getStage());
        assertEquals(403, error.getHttpStatus());
        assertEquals(JOB, error.getJobId());
        assertEquals("c6b7d182-723c-4027-9e88-e43b38da9fea", error.getTraceId());
        assertTrue(error.getMessage().contains("code=SignatureDoesNotMatch"));
        assertFalse(error.getMessage().contains("private signed URL"));
        assertFalse(error.toString().contains(TOKEN));
    }

    @Test
    void bosDiagnosticsDiscardCredentialAndUrlFields() {
        endpoint(JOBS, 200, done(base + "/result"));
        endpoint("/result", 403, "{\"code\":\"" + TOKEN
                + "\",\"requestId\":\"https://resource.invalid/?authorization=private\"}");
        var error = assertThrows(PpStructureApiException.class, () -> client().awaitResult(JOB));
        assertNull(error.getTraceId());
        assertFalse(error.toString().contains(TOKEN));
        assertFalse(error.toString().contains("authorization"));
        assertFalse(error.getMessage().contains("code="));
    }

    @Test
    void downloadMalformedJsonlRetainsDecodeStage() {
        endpoint(JOBS, 200, done(base + "/result"));
        endpoint("/result", 200, "not json " + TOKEN);
        var error = assertThrows(PpStructureApiException.class, () -> client().awaitResult(JOB));
        assertEquals(RESULT_DECODE, error.getStage());
        assertFalse(error.toString().contains(TOKEN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.invalid/file", "/relative", "https://user:pass@example.invalid/file"})
    void unsafeResourceSchemesAreRejectedBeforeDownload(String url) {
        endpoint(JOBS, 200, done(url));
        assertEquals(RESULT_DOWNLOAD, assertThrows(PpStructureApiException.class, () -> client().awaitResult(JOB)).getStage());
        assertEquals(1, requests.size());
    }

    @Test
    void redirectNeverForwardsApiToken() {
        server.createContext(JOBS, e -> {
            record(e); e.getResponseHeaders().set("Location", base + "/redirect");
            respond(e, 307, "{}", false);
        });
        endpoint("/redirect", 200, "{}");
        assertEquals(307, assertThrows(PpStructureApiException.class, () -> client().submitPdf(pdf)).getHttpStatus());
        assertEquals(1, requests.size());
    }

    @Test
    void invalidInputsNeverSendHttp() throws Exception {
        var client = client();
        Path empty = Files.createFile(directory.resolve("empty.pdf"));
        Path fake = Files.writeString(directory.resolve("fake.pdf"), "not a pdf");
        Path wrongExtension = Files.writeString(directory.resolve("file.txt"), "%PDF-1.7");
        for (Path invalid : List.of(directory.resolve("missing.pdf"), directory, empty, fake, wrongExtension)) {
            assertEquals(SUBMIT, assertThrows(PpStructureApiException.class, () -> client.submitPdf(invalid)).getStage());
        }
        assertThrows(PpStructureApiException.class, () -> client.submitPdf(null));
        assertTrue(requests.isEmpty());
    }

    private void endpoint(String path, int status, String body) {
        server.createContext(path, e -> { record(e); respond(e, status, body, false); });
    }

    private void delayedEndpoint(String path, String body, long delay) {
        server.createContext(path, e -> {
            record(e);
            try { Thread.sleep(delay); respond(e, 200, body, false); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); e.close(); }
            catch (IOException disconnected) { e.close(); }
        });
    }

    private void record(HttpExchange e) throws IOException {
        requests.add(new Request(e.getRequestMethod(), e.getRequestURI().getPath(),
                e.getRequestHeaders().getFirst("Authorization"), e.getRequestHeaders().getFirst("Content-Type"),
                new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private void respond(HttpExchange e, int status, String body, boolean chunked) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json");
        e.sendResponseHeaders(status, chunked ? 0 : bytes.length);
        try (var out = e.getResponseBody()) { out.write(bytes); }
        finally { e.close(); }
    }

    private String state(String value) { return "{\"code\":0,\"data\":{\"state\":\"" + value + "\"}}"; }
    private String done(String url) {
        return "{\"code\":0,\"data\":{\"state\":\"done\",\"resultUrl\":{\"jsonUrl\":\"" + url + "\"}}}";
    }
    private String minimalJsonl() {
        return "{\"result\":{\"layoutParsingResults\":[{\"prunedResult\":{\"parsing_res_list\":[{\"block_label\":\"text\",\"block_content\":\"正文\"}]}}]}}";
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void remoteSubmitAndStatusErrorsNeverExposeSignedUrls(boolean statusFailure) throws Exception {
        String message = "MinIO https://minio.invalid/a?X-Amz-Signature=private-signature "
                + "BOS https://bos.invalid/a?authorization=bce-auth-v1/private-token " + TOKEN;
        var body = json.createObjectNode();
        body.put("code", statusFailure ? 0 : 10007);
        body.put("traceId", "trace-safe");
        var data = body.putObject("data");
        if (statusFailure) data.put("state", "failed").put("errorMsg", message);
        else body.put("msg", message);
        endpoint(JOBS, 200, json.writeValueAsString(body));
        var failure = assertThrows(PpStructureApiException.class,
                () -> { if (statusFailure) client().awaitResult(JOB); else client().submitPdf(pdf); });
        assertEquals(statusFailure ? STATUS : SUBMIT, failure.getStage());
        assertEquals(200, failure.getHttpStatus());
        assertEquals("trace-safe", failure.getTraceId());
        assertFalse(failure.toString().contains("X-Amz-Signature"));
        assertFalse(failure.toString().contains("authorization=bce-auth-v1"));
        assertFalse(failure.toString().contains("private-signature"));
        assertFalse(failure.toString().contains("private-token"));
        assertFalse(failure.toString().contains(TOKEN));
    }

    @Test
    void mixedValidAndMalformedResultPagesFailAtDecodeStage() {
        endpoint(JOBS, 200, done(base + "/result"));
        endpoint("/result", 200, "{\"result\":{\"layoutParsingResults\":["
                + "{\"prunedResult\":{\"parsing_res_list\":[{\"block_label\":\"text\",\"block_content\":\"正文\"}]}},{}]}}");
        assertEquals(RESULT_DECODE, assertThrows(PpStructureApiException.class,
                () -> client().awaitResult(JOB)).getStage());
    }
    private record Request(String method, String path, String auth, String contentType, String body) {}
}
