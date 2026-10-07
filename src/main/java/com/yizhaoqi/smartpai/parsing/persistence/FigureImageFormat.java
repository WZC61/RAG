package com.yizhaoqi.smartpai.parsing.persistence;

import java.io.IOException;

/** Detects the existing supported figure formats by signature, without decoding the image. */
public final class FigureImageFormat {
    private FigureImageFormat() {}

    public static String detect(byte[] bytes) throws IOException {
        if (bytes != null) {
            if (startsWith(bytes, 0, 0xff, 0xd8, 0xff)) return "image/jpeg";
            if (startsWith(bytes, 0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return "image/png";
            if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')
                    && (startsWith(bytes, 12, 'V', 'P', '8', ' ') || startsWith(bytes, 12, 'V', 'P', '8', 'L')
                    || startsWith(bytes, 12, 'V', 'P', '8', 'X'))) return "image/webp";
        }
        throw new IOException("Figure response body is not a supported JPEG, PNG or WebP image");
    }

    private static boolean startsWith(byte[] bytes, int offset, int... signature) {
        if (bytes.length < offset + signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[offset + i] & 0xff) != signature[i]) return false;
        }
        return true;
    }
}
