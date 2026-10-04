package com.yizhaoqi.smartpai.parsing.persistence;

import com.yizhaoqi.smartpai.parsing.model.ParsedFigureContent;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Fake HTTP connections and mocked MinIO: no sockets or external services. */
class FigureImageStorageServiceTest {
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private MinioClient minio;
    private FigureImageStorageService storage;
    private FakeConnection connection;
    private int opened;
    private final List<String> uploadedPaths = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        minio = mock(MinioClient.class);
        connection = new FakeConnection(200, "image/jpeg", JPEG, -1);
        storage = new FigureImageStorageService(minio, "uploads", 1234, 5678, 8, uri -> {
            opened++;
            return connection;
        });
        doAnswer(invocation -> {
            PutObjectArgs args = invocation.getArgument(0);
            uploadedPaths.add(args.object());
            assertEquals("uploads", args.bucket());
            assertArrayEquals(connection.body, args.stream().readAllBytes());
            return null;
        }).when(minio).putObject(any());
    }

    @Test
    void successfulDownloadUsesMimeExtensionStablePathAndClosesResources() throws Exception {
        connection.mime = "Image/JPEG; charset=binary";
        assertEquals("figures/abc123/1/page-3-figure-2.jpg", storage.store("abc123", 1, figure("https://images.invalid/file.png?signature=secret")));
        assertEquals(List.of("figures/abc123/1/page-3-figure-2.jpg"), uploadedPaths);
        ArgumentCaptor<PutObjectArgs> capture = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(capture.capture());
        assertEquals("image/jpeg", capture.getValue().contentType());
        assertTrue(connection.closed);
        assertTrue(connection.disconnected);
    }

    @Test
    void sameGenerationRetriesUseTheSameKey() throws Exception {
        String first = storage.store("abc123", 1, figure("https://images.invalid/one"));
        String second = storage.store("abc123", 1, figure("https://images.invalid/two"));
        assertEquals(first, second);
        assertEquals(List.of(first, first), uploadedPaths);
    }

    @Test
    void newGenerationHasItsOwnDirectory() throws Exception {
        assertNotEquals(storage.store("abc123", 1, figure("https://images.invalid/image")),
                storage.store("abc123", 2, figure("https://images.invalid/image")));
        assertEquals("figures/abc123/2/page-3-figure-2.jpg", uploadedPaths.get(1));
    }

    @Test
    void missingUrlFailsBeforeOpeningHttpOrWritingMinio() {
        assertThrows(IOException.class, () -> storage.store("abc123", 1, figure(null)));
        assertEquals(0, opened);
        verifyNoInteractions(minio);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "file:///tmp/image.png", "ftp://images.invalid/a", "data:image/png;base64,abc", "https://user:pass@images.invalid/a", "https:///missing-host"})
    void invalidUrlsAreRejected(String url) {
        assertThrows(IOException.class, () -> storage.store("abc123", 1, figure(url)));
        assertEquals(0, opened);
        verifyNoInteractions(minio);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 403, 404, 500})
    void non2xxIncludingRedirectsNeverUpload(int status) {
        connection.status = status;
        IOException failure = assertThrows(IOException.class, () -> storage.store("abc123", 1, figure("https://images.invalid/a")));
        assertTrue(failure.getMessage().contains(Integer.toString(status)));
        assertTrue(connection.disconnected);
        assertFalse(connection.bodyRequested);
        verifyNoInteractions(minio);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/json", "image/", "image/*"})
    void nonImageMimeTypesNeverUpload(String mime) {
        connection.mime = mime;
        assertThrows(IOException.class, () -> storage.store("abc123", 1, figure("https://images.invalid/a")));
        assertFalse(connection.bodyRequested);
        verifyNoInteractions(minio);
    }

    @Test
    void missingContentTypeUsesJpegSignature() throws Exception {
        connection.mime = null;
        assertTrue(storage.store("abc123", 1, figure("https://images.invalid/a")).endsWith(".jpg"));
    }

    @Test
    void oversizedContentLengthFailsBeforeReadingBody() {
        connection.declaredLength = 9;
        assertThrows(IOException.class, () -> storage.store("abc123", 1, figure("https://images.invalid/a")));
        assertFalse(connection.bodyRequested);
        verifyNoInteractions(minio);
    }

    @Test
    void unknownLengthBodyIsLimitedWhileReading() throws Exception {
        connection = new FakeConnection(200, "image/png", new byte[9], -1);
        assertThrows(IOException.class, () -> storage.store("abc123", 1, figure("https://images.invalid/a")));
        assertTrue(connection.closed);
        assertTrue(connection.disconnected);
        verifyNoInteractions(minio);
    }

    @Test
    void misleadingSmallContentLengthDoesNotBypassBodyLimit() throws Exception {
        connection = new FakeConnection(200, "image/png", new byte[9], 1);
        assertThrows(IOException.class, () -> storage.store("abc123", 1, figure("https://images.invalid/a")));
        verifyNoInteractions(minio);
    }

    @Test
    void exactSizeLimitIsAccepted() throws Exception {
        connection = new FakeConnection(200, "image/png", PNG, 8);
        assertTrue(storage.store("abc123", 1, figure("http://images.invalid/a")).endsWith(".png"));
    }

    @Test
    void emptyImageResponseFails() throws Exception {
        connection = new FakeConnection(200, "image/png", new byte[0], 0);
        assertThrows(IOException.class, () -> storage.store("abc123", 1, figure("https://images.invalid/a")));
        verifyNoInteractions(minio);
    }

    @Test
    void configuredTimeoutsAndNoBearerHeaderAreApplied() throws Exception {
        storage.store("abc123", 1, figure("https://images.invalid/a"));
        assertEquals(1234, connection.getConnectTimeout());
        assertEquals(5678, connection.getReadTimeout());
        assertEquals("GET", connection.getRequestMethod());
        assertEquals("image/*", connection.getRequestProperty("Accept"));
        assertNull(connection.getRequestProperty("Authorization"));
        assertFalse(connection.getInstanceFollowRedirects());
    }

    @Test
    void downloadTimeoutDisconnectsAndNeverUploads() {
        connection.responseFailure = new SocketTimeoutException("fake timeout");
        assertThrows(SocketTimeoutException.class, () -> storage.store("abc123", 1, figure("https://images.invalid/a")));
        assertTrue(connection.disconnected);
        verifyNoInteractions(minio);
    }

    @Test
    void minioFailureDoesNotReturnACompletedPath() throws Exception {
        doThrow(new IOException("MinIO unavailable")).when(minio).putObject(any());
        IOException failure = assertThrows(IOException.class, () -> storage.store("abc123", 1, figure("https://images.invalid/a")));
        assertTrue(failure.getMessage().contains("MinIO"));
        assertTrue(connection.closed);
        assertTrue(connection.disconnected);
    }

    @Test
    void unknownImageMimeUsesActualSignatureExtension() throws Exception {
        connection.mime = "image/x-custom";
        assertTrue(storage.store("abc123", 1, figure("https://images.invalid/looks-like.png")).endsWith(".jpg"));
    }

    @ParameterizedTest
    @MethodSource("supportedImages")
    void supportedSignaturesDetermineStoredMimeAndExtension(String header, byte[] body, String mime, String extension) throws Exception {
        connection = new FakeConnection(200, header, body, -1);
        assertEquals("figures/abc123/1/page-3-figure-2." + extension,
                storage.store("abc123", 1, figure("https://images.invalid/wrong-extension.txt?signature=secret")));
        ArgumentCaptor<PutObjectArgs> capture = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(capture.capture());
        assertEquals(mime, capture.getValue().contentType());
        assertNull(connection.getRequestProperty("Authorization"));
        assertTrue(connection.closed);
        assertTrue(connection.disconnected);
    }

    private static Stream<Arguments> supportedImages() {
        return Stream.of(
                Arguments.of("image/jpeg", JPEG, "image/jpeg", "jpg"),
                Arguments.of("image/png", PNG, "image/png", "png"),
                Arguments.of("application/octet-stream", JPEG, "image/jpeg", "jpg"),
                Arguments.of("application/octet-stream; charset=binary", PNG, "image/png", "png"),
                Arguments.of(null, JPEG, "image/jpeg", "jpg"),
                Arguments.of(null, PNG, "image/png", "png"),
                Arguments.of("", JPEG, "image/jpeg", "jpg"),
                Arguments.of("image/jpeg", PNG, "image/png", "png"));
    }

    @ParameterizedTest
    @MethodSource("invalidImageBodies")
    void invalidBodiesNeverReachMinio(String header, byte[] body) throws Exception {
        connection = new FakeConnection(200, header, body, -1);
        IOException failure = assertThrows(IOException.class,
                () -> storage.store("abc123", 1, figure("https://images.invalid/a?signature=secret")));
        assertFalse(failure.getMessage().contains("secret"));
        assertTrue(connection.disconnected);
        verifyNoInteractions(minio);
    }

    private static Stream<Arguments> invalidImageBodies() {
        byte[] html = "<html>".getBytes(StandardCharsets.US_ASCII);
        byte[] json = "{\"e\":1}".getBytes(StandardCharsets.US_ASCII);
        return Stream.of(
                Arguments.of("text/html", html), Arguments.of("application/json", json),
                Arguments.of("image/jpeg", html), Arguments.of("image/png", json),
                Arguments.of("application/octet-stream", html), Arguments.of(null, json),
                Arguments.of("application/octet-stream", new byte[]{1, 2, 3}),
                Arguments.of("image/jpeg", new byte[]{(byte) 0xff, (byte) 0xd8}),
                Arguments.of("image/png", new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/webp", "application/octet-stream", ""})
    void webpSignatureIsSupported(String header) throws Exception {
        byte[] webp = {'R', 'I', 'F', 'F', 16, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', 'L', 0};
        connection = new FakeConnection(200, header, webp, -1);
        storage = new FigureImageStorageService(minio, "uploads", 1234, 5678, 24, uri -> connection);
        assertTrue(storage.store("abc123", 1, figure("https://images.invalid/a")).endsWith(".webp"));
        ArgumentCaptor<PutObjectArgs> capture = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(capture.capture());
        assertEquals("image/webp", capture.getValue().contentType());
    }

    @Test
    void riffWithoutWebpSignatureIsRejected() throws Exception {
        connection = new FakeConnection(200, "image/webp", "RIFFxxxxWAVEfmt ".getBytes(StandardCharsets.US_ASCII), -1);
        storage = new FigureImageStorageService(minio, "uploads", 1234, 5678, 24, uri -> connection);
        assertThrows(IOException.class, () -> storage.store("abc123", 1, figure("https://images.invalid/a")));
        verifyNoInteractions(minio);
    }

    @Test
    void responseLogRecordsContentTypeWithoutSignedUrl() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(FigureImageStorageService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            connection.mime = "application/octet-stream";
            storage.store("abc123", 1, figure("https://images.invalid/a?signature=secret"));
            assertEquals(1, appender.list.size());
            String message = appender.list.get(0).getFormattedMessage();
            assertTrue(message.contains("Content-Type=application/octet-stream"));
            assertTrue(message.contains("fileMd5=abc123"));
            assertFalse(message.contains("secret"));
            assertFalse(message.contains("https://"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void invalidGenerationAndPathIdentityDoNotOpenHttp() {
        assertThrows(IllegalArgumentException.class, () -> storage.store("../abc", 1, figure("https://images.invalid/a")));
        assertThrows(IllegalArgumentException.class, () -> storage.store("abc123", 0, figure("https://images.invalid/a")));
        assertEquals(0, opened);
    }

    private static ParsedFigureContent figure(String url) {
        return new ParsedFigureContent(3, 2, "Figure 2", List.of(0, 1, 2, 3), "caption", "ocr", "nearby", "key", url);
    }

    private static class FakeConnection extends HttpURLConnection {
        int status;
        String mime;
        final byte[] body;
        long declaredLength;
        boolean closed;
        boolean disconnected;
        boolean bodyRequested;
        IOException responseFailure;

        FakeConnection(int status, String mime, byte[] body, long declaredLength) throws Exception {
            super(URI.create("https://images.invalid/fake").toURL());
            this.status = status;
            this.mime = mime;
            this.body = body;
            this.declaredLength = declaredLength;
        }

        @Override public void connect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnected = true; }
        @Override public int getResponseCode() throws IOException {
            if (responseFailure != null) throw responseFailure;
            return status;
        }
        @Override public String getContentType() { return mime; }
        @Override public long getContentLengthLong() { return declaredLength; }
        @Override public InputStream getInputStream() {
            bodyRequested = true;
            return new ByteArrayInputStream(body) {
                @Override public void close() throws IOException {
                    closed = true;
                    super.close();
                }
            };
        }
    }
}
