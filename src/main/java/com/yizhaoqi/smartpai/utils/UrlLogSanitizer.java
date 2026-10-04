package com.yizhaoqi.smartpai.utils;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Removes resource URLs and credentials from diagnostics, never from actual HTTP requests. */
public final class UrlLogSanitizer {
    private static final Pattern URL = Pattern.compile("(?i)https?(?:://|%3a%2f%2f)[^\\s<>\"']+");
    private static final Pattern AUTH = Pattern.compile(
            "(?i)(?:X-Amz-[a-z0-9-]+|authorization|access_token)\\s*=\\s*[^\\s&\"'<>]+"
                    + "|bce-auth-v1(?:/|%2f)[^\\s\"'<>]+|Bearer\\s+[^\\s\"'<>]+");

    private UrlLogSanitizer() {}

    public static String redact(String value) {
        if (value == null) return null;
        return AUTH.matcher(URL.matcher(value).replaceAll("[REDACTED_URL]"))
                .replaceAll("[REDACTED_AUTH]");
    }

    /** A safe cause chain also prevents framework retry logs from printing the original URL. */
    public static Throwable exception(Throwable failure) {
        if (isSafe(failure, new IdentityHashMap<>())) return failure;
        return copy(failure, new IdentityHashMap<>());
    }

    private static boolean isSafe(Throwable failure, Map<Throwable, Boolean> seen) {
        if (failure == null || seen.put(failure, true) != null) return true;
        if (!java.util.Objects.equals(failure.getMessage(), redact(failure.getMessage()))
                || !isSafe(failure.getCause(), seen)) return false;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (!isSafe(suppressed, seen)) return false;
        }
        return true;
    }

    private static Throwable copy(Throwable failure, Map<Throwable, Throwable> seen) {
        if (failure == null) return null;
        if (seen.containsKey(failure)) return seen.get(failure);
        String message = redact(failure.getMessage());
        Throwable safe;
        // Retain common retry classifications and original stack locations.
        if (failure instanceof IllegalArgumentException) safe = new IllegalArgumentException(message);
        else if (failure instanceof IllegalStateException) safe = new IllegalStateException(message);
        else if (failure instanceof IOException) safe = new IOException(message);
        else safe = new RuntimeException(failure.getClass().getName() + ": " + message);
        safe.setStackTrace(failure.getStackTrace());
        seen.put(failure, safe);
        Throwable cause = copy(failure.getCause(), seen);
        if (cause != safe) safe.initCause(cause);
        for (Throwable suppressed : failure.getSuppressed()) {
            Throwable sanitized = copy(suppressed, seen);
            if (sanitized != safe) safe.addSuppressed(sanitized);
        }
        return safe;
    }
}
