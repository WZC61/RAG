package com.yizhaoqi.smartpai.parsing.persistence;

import com.yizhaoqi.smartpai.parsing.model.ParsedFigureContent;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Downloads bounded image bodies without Paddle credentials, then uploads to stable MinIO keys. */
@Service
public class FigureImageStorageService {
    private static final Logger log = LoggerFactory.getLogger(FigureImageStorageService.class);
    private final MinioClient minio;
    private final String bucket;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxImageBytes;
    private final ConnectionFactory connections;

    @Autowired
    public FigureImageStorageService(MinioClient minio,
            @Value("${minio.bucketName:uploads}") String bucket,
            @Value("${file.parsing.figures.connect-timeout-millis:10000}") int connectTimeoutMillis,
            @Value("${file.parsing.figures.read-timeout-millis:30000}") int readTimeoutMillis,
            @Value("${file.parsing.figures.max-image-bytes:10485760}") int maxImageBytes) {
        this(minio, bucket, connectTimeoutMillis, readTimeoutMillis, maxImageBytes,
                uri -> (HttpURLConnection) uri.toURL().openConnection());
    }

    FigureImageStorageService(MinioClient minio, String bucket, int connectTimeoutMillis,
                              int readTimeoutMillis, int maxImageBytes, ConnectionFactory connections) {
        this.minio = Objects.requireNonNull(minio, "minio");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        if (bucket.isBlank() || connectTimeoutMillis < 1 || readTimeoutMillis < 1 || maxImageBytes < 1)
            throw new IllegalArgumentException("Invalid figure storage configuration");
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxImageBytes = maxImageBytes;
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public String store(String fileMd5, long generation, ParsedFigureContent figure) throws IOException {
        Objects.requireNonNull(figure, "figure");
        String prefix = ArtifactValidation.figurePathPrefix(fileMd5, generation, figure.pageNumber(), figure.figureIndex());
        URI uri = imageUri(figure.sourceImageUrl());
        HttpURLConnection connection = connections.open(uri);
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            // Reject redirects instead of following an unchecked scheme/host.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "image/*");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Figure download HTTP status " + status);
            String responseType = normalizedContentType(connection.getContentType());
            // Only log the media type and stable identity, never the signed resource URL.
            log.info("Figure download response: fileMd5={}, generation={}, page={}, figure={}, Content-Type={}",
                    fileMd5, generation, figure.pageNumber(), figure.figureIndex(),
                    responseType.isEmpty() ? "<missing>" : responseType);
            if (!responseType.isEmpty() && !responseType.equals("application/octet-stream")
                    && !responseType.matches("image/[a-z0-9][a-z0-9.+-]*"))
                throw new IOException("Figure response Content-Type is not an image or binary stream");
            if (connection.getContentLengthLong() > maxImageBytes)
                throw new IOException("Figure image exceeds maximum size");
            byte[] bytes;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if ((long) output.size() + count > maxImageBytes)
                        throw new IOException("Figure image exceeds maximum size");
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            if (bytes.length == 0) throw new IOException("Figure image response is empty");
            String contentType = imageContentType(bytes);
            String path = prefix + extension(contentType);
            try (InputStream input = new ByteArrayInputStream(bytes)) {
                minio.putObject(PutObjectArgs.builder().bucket(bucket).object(path)
                        .contentType(contentType).stream(input, bytes.length, -1).build());
            } catch (Exception e) {
                throw new IOException("Failed to store figure image in MinIO", e);
            }
            return path;
        } finally {
            connection.disconnect();
        }
    }

    private static URI imageUri(String source) throws IOException {
        if (source == null || source.isBlank()) throw new IOException("Figure sourceImageUrl is missing");
        try {
            URI uri = URI.create(source);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("Invalid image URI");
            return uri;
        } catch (IllegalArgumentException e) {
            // Do not expose temporary signed query parameters in error messages.
            throw new IOException("Figure sourceImageUrl must be an HTTP(S) URL without user-info");
        }
    }

    private static String normalizedContentType(String header) {
        String type = header == null ? "" : header.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        // A malformed/untrusted header must not inject log lines or credentials.
        return type.isEmpty() || type.matches("[a-z0-9.+-]+/[a-z0-9.+*-]+") ? type : "<invalid>";
    }

    private static String imageContentType(byte[] bytes) throws IOException {
        if (startsWith(bytes, 0, 0xff, 0xd8, 0xff)) return "image/jpeg";
        if (startsWith(bytes, 0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return "image/png";
        if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')
                && (startsWith(bytes, 12, 'V', 'P', '8', ' ') || startsWith(bytes, 12, 'V', 'P', '8', 'L')
                || startsWith(bytes, 12, 'V', 'P', '8', 'X'))) return "image/webp";
        throw new IOException("Figure response body is not a supported JPEG, PNG or WebP image");
    }

    private static boolean startsWith(byte[] bytes, int offset, int... signature) {
        if (bytes.length < offset + signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[offset + i] & 0xff) != signature[i]) return false;
        }
        return true;
    }

    private static String extension(String type) {
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("Unsupported figure image format");
        };
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws IOException;
    }
}
