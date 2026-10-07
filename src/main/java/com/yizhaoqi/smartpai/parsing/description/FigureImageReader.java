package com.yizhaoqi.smartpai.parsing.description;

import com.yizhaoqi.smartpai.client.FigureDescriptionProperties;
import com.yizhaoqi.smartpai.parsing.persistence.FigureImageFormat;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Reads bounded figure bytes through the MinIO SDK; no temporary URL is exposed to the model. */
@Component
public class FigureImageReader {
    private final MinioClient minio;
    private final String bucket;
    private final int maxImageBytes;

    public FigureImageReader(MinioClient minio, FigureDescriptionProperties properties,
            @Value("${minio.bucketName:uploads}") String bucket) {
        this.minio = Objects.requireNonNull(minio, "minio");
        Objects.requireNonNull(properties, "properties");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        long configuredLimit = properties.getMaxImageBytes();
        if (bucket.isBlank() || configuredLimit < 1 || configuredLimit >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid figure image reader configuration");
        this.maxImageBytes = (int) configuredLimit;
    }

    public FigureImage read(String imagePath) throws IOException {
        validatePath(imagePath);
        byte[] bytes;
        try (InputStream input = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(imagePath).build())) {
            if (input == null) throw new IOException("MinIO figure response is missing");
            bytes = input.readNBytes(maxImageBytes + 1);
        } catch (Exception e) {
            // Remote messages/causes can contain authorization material or URLs; keep them out of callers' logs.
            throw new IOException("Failed to read figure image from MinIO");
        }
        if (bytes.length == 0) throw new IOException("Figure image is empty");
        if (bytes.length > maxImageBytes) throw new IOException("Figure image exceeds maximum size");
        return new FigureImage(bytes, FigureImageFormat.detect(bytes));
    }

    private static void validatePath(String path) throws IOException {
        if (path == null || !path.startsWith("figures/") || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0 || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                || path.chars().anyMatch(Character::isISOControl))
            throw new IOException("Figure imagePath must be a relative figure object path");
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals(".."))
                throw new IOException("Figure imagePath must be a relative figure object path");
        }
    }

    public record FigureImage(byte[] bytes, String mimeType) {}
}
