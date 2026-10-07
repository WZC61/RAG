package com.yizhaoqi.smartpai.parsing.description;

import com.yizhaoqi.smartpai.client.FigureDescriptionProperties;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FigureImageReaderTest {
    private static final String PATH = "figures/abc123/1/page-2-figure-3.jpg";
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 16, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', 'L', 0};
    private MinioClient minio;
    private FigureDescriptionProperties properties;
    private FigureImageReader reader;

    @BeforeEach
    void setUp() {
        minio = mock(MinioClient.class);
        properties = new FigureDescriptionProperties();
        properties.setMaxImageBytes(24);
        reader = new FigureImageReader(minio, properties, "uploads");
    }

    @ParameterizedTest
    @MethodSource("supportedImages")
    void supportedImageBytesUseRealMimeAndCloseResponse(byte[] bytes, String mime) throws Exception {
        TrackingStream input = source(bytes, "application/json");
        FigureImageReader.FigureImage image = reader.read(PATH);
        assertArrayEquals(bytes, image.bytes());
        assertEquals(mime, image.mimeType());
        assertTrue(input.closed);
        ArgumentCaptor<GetObjectArgs> capture = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minio).getObject(capture.capture());
        assertEquals("uploads", capture.getValue().bucket());
        assertEquals(PATH, capture.getValue().object());
        verifyNoMoreInteractions(minio);
    }

    private static Stream<Arguments> supportedImages() {
        return Stream.of(Arguments.of(JPEG, "image/jpeg"), Arguments.of(PNG, "image/png"),
                Arguments.of(WEBP, "image/webp"));
    }

    @Test
    void exactSizeLimitIsAccepted() throws Exception {
        properties.setMaxImageBytes(PNG.length);
        reader = new FigureImageReader(minio, properties, "uploads");
        TrackingStream input = source(PNG, null);
        assertEquals("image/png", reader.read(PATH).mimeType());
        assertTrue(input.closed);
    }

    @Test
    void oversizedImageIsReadOnlyUpToLimitPlusOneAndClosed() throws Exception {
        properties.setMaxImageBytes(8);
        reader = new FigureImageReader(minio, properties, "uploads");
        TrackingStream input = source(new byte[100], null);
        IOException failure = assertThrows(IOException.class, () -> reader.read(PATH));
        assertTrue(failure.getMessage().contains("maximum size"));
        assertEquals(9, input.readBytes);
        assertTrue(input.closed);
    }

    @Test
    void emptyImageFailsAndClosesResponse() throws Exception {
        TrackingStream input = source(new byte[0], null);
        IOException failure = assertThrows(IOException.class, () -> reader.read(PATH));
        assertTrue(failure.getMessage().contains("empty"));
        assertTrue(input.closed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html>error</html>", "{\"error\":\"bad\"}", "GIF89a", "RIFFxxxxWAVEfmt "})
    void unsupportedBodiesFailAndCloseResponse(String body) throws Exception {
        TrackingStream input = source(body.getBytes(StandardCharsets.US_ASCII), "image/jpeg");
        assertThrows(IOException.class, () -> reader.read(PATH));
        assertTrue(input.closed);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "/figures/abc/1/p.jpg", "merged/abc", "https://localhost/figures/a.jpg",
            "figures/../a.jpg", "figures/./a.jpg", "figures//a.jpg", "figures/", "figures/a\\b.jpg",
            "figures/a?X-Amz-Signature=secret", "figures/a#frag", "figures/a\nb.jpg"})
    void invalidObjectPathsFailBeforeReadingMinio(String path) {
        IOException failure = assertThrows(IOException.class, () -> reader.read(path));
        assertFalse(failure.getMessage().contains("secret"));
        verifyNoInteractions(minio);
    }

    @Test
    void missingImageFailsWithoutRemoteSecretsOrCause() throws Exception {
        when(minio.getObject(any())).thenThrow(new IOException("404 https://host/image?X-Amz-Signature=secret"));
        IOException failure = assertThrows(IOException.class, () -> reader.read(PATH));
        assertEquals("Failed to read figure image from MinIO", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void missingResponseIsRejected() throws Exception {
        when(minio.getObject(any())).thenReturn(null);
        assertThrows(IOException.class, () -> reader.read(PATH));
    }

    @Test
    void readFailureClosesStreamAndSanitizesCause() throws Exception {
        TrackingStream input = source(JPEG, null);
        input.failRead = true;
        IOException failure = assertThrows(IOException.class, () -> reader.read(PATH));
        assertEquals("Failed to read figure image from MinIO", failure.getMessage());
        assertNull(failure.getCause());
        assertTrue(input.closed);
    }

    @Test
    void closeFailureIsSanitized() throws Exception {
        TrackingStream input = source(JPEG, null);
        input.failClose = true;
        IOException failure = assertThrows(IOException.class, () -> reader.read(PATH));
        assertEquals("Failed to read figure image from MinIO", failure.getMessage());
        assertNull(failure.getCause());
        assertTrue(input.closed);
    }

    @Test
    void invalidLimitFailsBeforeAccessingMinio() {
        properties.setMaxImageBytes(0);
        assertThrows(IllegalArgumentException.class, () -> new FigureImageReader(minio, properties, "uploads"));
        properties.setMaxImageBytes(Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> new FigureImageReader(minio, properties, "uploads"));
        verifyNoInteractions(minio);
    }

    private TrackingStream source(byte[] bytes, String header) throws Exception {
        TrackingStream input = new TrackingStream(bytes);
        Headers.Builder headers = new Headers.Builder();
        if (header != null) headers.add("Content-Type", header);
        GetObjectResponse response = new GetObjectResponse(headers.build(), "uploads", "", PATH, input);
        when(minio.getObject(any())).thenReturn(response);
        return input;
    }

    private static class TrackingStream extends InputStream {
        private final ByteArrayInputStream body;
        boolean closed;
        boolean failRead;
        boolean failClose;
        int readBytes;

        TrackingStream(byte[] bytes) { body = new ByteArrayInputStream(bytes); }

        @Override
        public int read() throws IOException {
            if (failRead) throw new IOException("read failed https://host?X-Amz-Signature=secret");
            int value = body.read();
            if (value >= 0) readBytes++;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (failRead) throw new IOException("read failed https://host?X-Amz-Signature=secret");
            int count = body.read(bytes, offset, length);
            if (count > 0) readBytes += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            body.close();
            if (failClose) throw new IOException("close failed https://host?X-Amz-Signature=secret");
        }
    }
}
