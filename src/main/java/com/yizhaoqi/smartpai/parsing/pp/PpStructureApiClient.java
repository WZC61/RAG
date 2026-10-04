package com.yizhaoqi.smartpai.parsing.pp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.parsing.PpStructureResultMapper;
import com.yizhaoqi.smartpai.parsing.model.DocumentParseResult;
import com.yizhaoqi.smartpai.parsing.pp.PpStructureApiException.Stage;
import com.yizhaoqi.smartpai.utils.UrlLogSanitizer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.yizhaoqi.smartpai.parsing.pp.PpStructureApiException.Stage.*;

/** Official async jobs protocol. No persistence, business retries or image downloads. */
public final class PpStructureApiClient {
    private static final String JOBS_PATH = "/api/v2/ocr/jobs";
    private static final int API_MAX_BYTES = 1024 * 1024;
    private static final long PDF_MAX_BYTES = 50L * 1024 * 1024;
    private final WebClient api;
    private final WebClient resources;
    private final ObjectMapper json;
    private final PpStructureResultDecoder decoder;
    private final PpStructureResultMapper mapper;
    private final String token;
    private final String model;
    private final Duration requestTimeout;
    private final Duration pollTimeout;
    private final Duration initialInterval;
    private final Duration maxInterval;
    private final int resultMaxBytes;

    public PpStructureApiClient(PpStructureApiProperties properties, ObjectMapper json,
                                PpStructureResultDecoder decoder, PpStructureResultMapper mapper) {
        if (!properties.isEnabled()) throw new IllegalArgumentException("PP API is disabled");
        token = properties.getAccessToken();
        if (token == null || token.isBlank() || token.contains("\r") || token.contains("\n"))
            throw new IllegalArgumentException("PP API requires a valid access token");
        model = properties.getModel();
        if (!"PP-StructureV3".equals(model)) throw new IllegalArgumentException("PP API model must be PP-StructureV3");
        URI base = httpUri(properties.getBaseUrl(), SUBMIT, null);
        if (base.getQuery() != null) throw new IllegalArgumentException("PP API base URL must not have a query");
        requestTimeout = positive(properties.getRequestTimeout());
        pollTimeout = positive(properties.getPollTimeout());
        initialInterval = positive(properties.getInitialPollInterval());
        maxInterval = positive(properties.getMaxPollInterval());
        if (initialInterval.compareTo(maxInterval) > 0)
            throw new IllegalArgumentException("Initial poll interval exceeds maximum");
        resultMaxBytes = properties.getResultMaxBytes();
        if (resultMaxBytes <= 0) throw new IllegalArgumentException("Result max bytes must be positive");
        this.json = json;
        this.decoder = decoder;
        this.mapper = mapper;
        // Disable Reactor Netty's connection-reset retry too: POST has no confirmed idempotency key.
        // Redirects stay disabled; no request can forward its credentials to a redirect target.
        HttpClient transport = HttpClient.create().disableRetry(true).followRedirect(false);
        api = WebClient.builder().baseUrl(base.toString())
                .clientConnector(new ReactorClientHttpConnector(transport)).build();
        // Netty adds Content-Length: 0 after WebClient prepares a bodyless GET. BOS
        // result URLs signed without that header reject it as SignatureDoesNotMatch.
        // Remove it at final HTTP write, on this resource request only, including pooled
        // connections. A WebClient filter or doOnRequest header removal is too early.
        HttpClient resourceTransport = transport.doOnRequest((request, connection) ->
                connection.addHandlerLast("pp-bodyless-result-get", new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise)
                            throws Exception {
                        if (message instanceof HttpRequest outgoing) {
                            if (outgoing.method().equals(HttpMethod.GET)
                                    && "0".equals(outgoing.headers().get(HttpHeaderNames.CONTENT_LENGTH)))
                                outgoing.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
                            context.write(message, promise);
                            // One request only: never leave this handler on a pooled API connection.
                            context.pipeline().remove(this);
                        } else context.write(message, promise);
                    }
                }));
        resources = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(resourceTransport)).build();
    }

    public PpJob submitPdf(Path pdf) {
        checkInterrupted(SUBMIT, null);
        validatePdf(pdf);
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("model", model);
        // In multipart this field is a JSON string, not a nested JSON part or Base64 file.
        body.part("optionalPayload", "{\"useDocOrientationClassify\":false,"
                + "\"useDocUnwarping\":false,\"returnMarkdownImages\":true}");
        body.part("file", new FileSystemResource(pdf)).contentType(MediaType.APPLICATION_PDF);
        Reply reply = exchange(api.post().uri(JOBS_PATH).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build())), SUBMIT, null, API_MAX_BYTES, requestTimeout);
        JsonNode root = apiResponse(reply, SUBMIT, null);
        String jobId = text(root.path("data"), "jobId");
        if (jobId == null) throw protocol(SUBMIT, reply, root, null, "Submit response missing jobId");
        return new PpJob(jobId);
    }

    public PpJobStatus getStatus(String jobId) {
        return getStatus(jobId, requestTimeout);
    }

    private PpJobStatus getStatus(String jobId, Duration timeout) {
        checkJobId(jobId, STATUS);
        Reply reply = exchange(api.get().uri(builder -> builder.path(JOBS_PATH).pathSegment(jobId).build())
                .headers(h -> h.setBearerAuth(token)), STATUS, jobId, API_MAX_BYTES, timeout);
        JsonNode root = apiResponse(reply, STATUS, jobId);
        JsonNode data = root.path("data");
        String state = text(data, "state");
        if (state == null || !Set.of("pending", "running", "done", "failed").contains(state))
            throw protocol(STATUS, reply, root, jobId, "Unknown or missing job state");
        String returnedId = text(data, "jobId");
        if (returnedId != null && !jobId.equals(returnedId))
            throw protocol(STATUS, reply, root, jobId, "Status response jobId mismatch");
        String error = text(data, "errorMsg");
        if ("failed".equals(state))
            throw protocol(STATUS, reply, root, jobId, "PP job failed: " + (error == null ? "unspecified" : error));
        String resultUrl = text(data.path("resultUrl"), "jsonUrl");
        if ("done".equals(state) && resultUrl == null)
            throw protocol(STATUS, reply, root, jobId, "Done response missing resultUrl.jsonUrl");
        JsonNode progress = data.path("extractProgress");
        return new PpJobStatus(jobId, state, error, resultUrl, text(data.path("resultUrl"), "markdownUrl"),
                integer(progress.path("totalPages")), integer(progress.path("extractedPages")));
    }

    public DocumentParseResult parsePdf(Path pdf) {
        return awaitResult(submitPdf(pdf).jobId());
    }

    public DocumentParseResult awaitResult(String jobId) {
        checkJobId(jobId, POLL);
        long started = System.nanoTime();
        long interval = initialInterval.toNanos();
        while (true) {
            Duration remaining = remaining(started, jobId);
            PpJobStatus status;
            try {
                status = getStatus(jobId, minimum(requestTimeout, remaining));
            } catch (PpStructureApiException e) {
                remaining(started, jobId);
                throw e;
            }
            remaining(started, jobId);
            if ("done".equals(status.state())) {
                URI url = httpUri(status.resultJsonUrl(), RESULT_DOWNLOAD, jobId);
                Reply result;
                try {
                    result = exchange(resources.get().uri(url), RESULT_DOWNLOAD, jobId,
                            resultMaxBytes, minimum(requestTimeout, remaining(started, jobId)));
                } catch (PpStructureApiException e) {
                    remaining(started, jobId);
                    throw e;
                }
                remaining(started, jobId);
                if (result.status < 200 || result.status >= 300)
                    throw resultDownloadFailure(result, jobId);
                var pages = decoder.decode(new InputStreamReader(new ByteArrayInputStream(result.body),
                        StandardCharsets.UTF_8), safe(jobId));
                remaining(started, jobId);
                DocumentParseResult parsed;
                try {
                    parsed = mapper.map(pages);
                } catch (RuntimeException e) {
                    throw failure(RESULT_DECODE, null, null, null, jobId, "Result mapping failed");
                }
                remaining(started, jobId);
                return parsed;
            }
            long delay = Math.min(interval, remaining(started, jobId).toNanos());
            try {
                TimeUnit.NANOSECONDS.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure(POLL, null, null, null, jobId, "PP polling interrupted");
            }
            interval += Math.min(interval / 2, maxInterval.toNanos() - interval);
        }
    }

    private Reply exchange(WebClient.RequestHeadersSpec<?> request, Stage stage, String jobId,
                           int limit, Duration timeout) {
        checkInterrupted(stage, jobId);
        try {
            // Enforced on actual received bytes, including chunked responses without Content-Length.
            return request.exchangeToMono(response -> {
                int status = response.statusCode().value();
                return DataBufferUtils.join(response.bodyToFlux(DataBuffer.class), limit)
                        .map(buffer -> {
                            try {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                return bytes;
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        }).defaultIfEmpty(new byte[0])
                        .map(bytes -> new Reply(status, bytes))
                        .onErrorMap(DataBufferLimitException.class, e -> failure(stage, status,
                                null, null, jobId, "Response exceeds configured byte limit"));
            }).block(timeout);
        } catch (PpStructureApiException e) {
            throw e;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted() || causedBy(e, InterruptedException.class)) {
                Thread.currentThread().interrupt();
                throw failure(stage, null, null, null, jobId, "PP request interrupted");
            }
            String message = causedBy(e, TimeoutException.class) ? "PP request timed out" : "PP HTTP request failed";
            throw failure(stage, null, null, null, jobId, message);
        }
    }

    private PpStructureApiException resultDownloadFailure(Reply reply, String jobId) {
        String code = null;
        String requestId = null;
        try {
            JsonNode error = json.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(reply.body);
            if (error != null && error.isObject()) {
                code = diagnosticIdentifier(text(error, "code"));
                requestId = diagnosticIdentifier(text(error, "requestId"));
            }
        } catch (IOException ignored) {
            // Resource errors can also be HTML/plain text; never expose their raw body.
        }
        String message = "Result download HTTP error (HTTP " + reply.status
                + (code == null ? "" : ", code=" + code)
                + (requestId == null ? "" : ", requestId=" + requestId) + ")";
        return failure(RESULT_DOWNLOAD, reply.status, null, requestId, jobId, message);
    }

    private String diagnosticIdentifier(String value) {
        // Restrict diagnostics to short identifiers, excluding URLs and the configured token.
        return value != null && value.matches("[A-Za-z0-9_-]{1,80}") && !value.contains(token)
                ? value : null;
    }

    private JsonNode apiResponse(Reply reply, Stage stage, String jobId) {
        JsonNode root;
        try {
            root = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(reply.body);
        } catch (IOException e) {
            throw failure(stage, reply.status, null, null, jobId, "API response is not valid JSON");
        }
        if (root == null || !root.isObject())
            throw failure(stage, reply.status, null, null, jobId, "API response must be an object");
        Integer code = integer(root.path("code"));
        if (reply.status < 200 || reply.status >= 300 || code == null || code != 0) {
            String detail = text(root.path("data"), "errorMsg");
            if (detail == null) detail = text(root, "msg");
            throw protocol(stage, reply, root, jobId, "PP API rejected request" + (detail == null ? "" : ": " + detail));
        }
        if (!root.path("data").isObject())
            throw protocol(stage, reply, root, jobId, "API response missing data object");
        return root;
    }

    private void validatePdf(Path pdf) {
        try {
            if (pdf == null || !Files.isRegularFile(pdf) || !Files.isReadable(pdf)
                    || !pdf.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                throw new IOException();
            long size = Files.size(pdf);
            if (size == 0 || size > PDF_MAX_BYTES) throw new IOException();
            try (var input = Files.newInputStream(pdf)) {
                if (!"%PDF-".equals(new String(input.readNBytes(5), StandardCharsets.US_ASCII))) throw new IOException();
            }
        } catch (IOException | SecurityException e) {
            throw failure(SUBMIT, null, null, null, null,
                    "Expected a readable, nonempty PDF with %PDF- header, at most 50 MB");
        }
    }

    private URI httpUri(String value, Stage stage, String jobId) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw failure(stage, null, null, null, jobId, "Expected an HTTP(S) URL without user info or fragment");
        }
    }

    private Duration remaining(long started, String jobId) {
        checkInterrupted(POLL, jobId);
        long nanos = pollTimeout.toNanos() - (System.nanoTime() - started);
        if (nanos <= 0) throw failure(POLL, null, null, null, jobId, "PP polling deadline exceeded");
        return Duration.ofNanos(nanos);
    }

    private void checkInterrupted(Stage stage, String jobId) {
        if (Thread.currentThread().isInterrupted())
            throw failure(stage, null, null, null, jobId, "PP operation interrupted");
    }

    private void checkJobId(String jobId, Stage stage) {
        if (jobId == null || jobId.isBlank())
            throw failure(stage, null, null, null, jobId, "jobId must not be blank");
    }

    private PpStructureApiException protocol(Stage stage, Reply reply, JsonNode root, String jobId, String message) {
        return failure(stage, reply.status, integer(root.path("code")), text(root, "traceId"), jobId, message);
    }

    private PpStructureApiException failure(Stage stage, Integer status, Integer code, String trace,
                                           String jobId, String message) {
        return new PpStructureApiException(stage, status, code, safe(trace), safe(jobId), safe(message));
    }

    private String safe(String value) {
        return value == null ? null : UrlLogSanitizer.redact(value.replace(token, "[REDACTED]"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }

    private static Integer integer(JsonNode value) {
        if (value.isIntegralNumber() && value.canConvertToInt()) return value.intValue();
        if (value.isTextual()) {
            try { return Integer.valueOf(value.textValue().trim()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static Duration positive(Duration value) {
        if (value == null || value.isNegative() || value.isZero())
            throw new IllegalArgumentException("PP timeouts and intervals must be positive");
        try { value.toNanos(); } catch (ArithmeticException e) { throw new IllegalArgumentException("PP duration too large"); }
        return value;
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) < 0 ? first : second;
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) if (type.isInstance(cause)) return true;
        return false;
    }

    private record Reply(int status, byte[] body) {}
}
