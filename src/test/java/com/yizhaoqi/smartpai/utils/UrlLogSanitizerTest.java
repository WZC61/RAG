package com.yizhaoqi.smartpai.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UrlLogSanitizerTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Download https://minio.invalid/object?X-Amz-Signature=secret failed",
            "Download https://bos.invalid/result?authorization=bce-auth-v1/key/signature failed",
            "Download https%3A%2F%2Fbos.invalid%2Fresult%3Fauthorization%3Dbce-auth-v1%2Fsecret",
            "X-Amz-Signature=secret authorization=bce-auth-v1/key Bearer secret",
            "bce-auth-v1/key/time/signature"
    })
    void removesSignedResourcesAndAuthorization(String diagnostic) {
        String safe = UrlLogSanitizer.redact(diagnostic);
        assertFalse(safe.contains("X-Amz-Signature"));
        assertFalse(safe.contains("bce-auth-v1"));
        assertFalse(safe.contains("secret"));
        assertFalse(safe.contains("https"));
    }

    @Test
    void preservesOrdinaryDiagnostics() {
        assertEquals("HTTP 403, jobId=job-1", UrlLogSanitizer.redact("HTTP 403, jobId=job-1"));
        assertNull(UrlLogSanitizer.redact(null));
        IOException safe = new IOException("HTTP 403");
        assertSame(safe, UrlLogSanitizer.exception(safe));
    }

    @Test
    void sanitizesNestedAndSuppressedCausesWithoutLosingStackLocations() {
        IOException original = new IOException("https://bos.invalid/?authorization=bce-auth-v1/secret");
        original.addSuppressed(new IOException("X-Amz-Signature=secret"));
        Throwable safe = UrlLogSanitizer.exception(new IllegalArgumentException("HTTP failed", original));
        assertInstanceOf(IllegalArgumentException.class, safe);
        assertInstanceOf(IOException.class, safe.getCause());
        assertArrayEquals(original.getStackTrace(), safe.getCause().getStackTrace());
        StringWriter text = new StringWriter(); safe.printStackTrace(new PrintWriter(text));
        assertFalse(text.toString().contains("secret"));
        assertFalse(text.toString().contains("X-Amz-Signature"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void configKeepsSignedUriLoggerAtInfoWithoutDisablingOtherHttpDiagnostics() throws Exception {
        try (InputStream input = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            Map<String, Object> yaml = new Yaml().load(input);
            Map<String, Object> levels = (Map<String, Object>) ((Map<String, Object>) yaml.get("logging")).get("level");
            assertEquals("INFO", levels.get("org.springframework.web.reactive.function.client.ExchangeFunctions"));
            assertTrue(levels.get("org.springframework.web").toString().contains("LOG_LEVEL_WEB"));
        }
        String xml = Files.readString(Path.of("src/main/resources/logback-spring.xml"));
        assertTrue(xml.contains("name=\"org.springframework.web.reactive.function.client.ExchangeFunctions\" level=\"INFO\""));
    }
}
