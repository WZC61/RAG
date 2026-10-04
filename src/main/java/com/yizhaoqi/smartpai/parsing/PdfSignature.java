package com.yizhaoqi.smartpai.parsing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads only the PDF header and restores the stream for the actual parser. */
public final class PdfSignature {
    private PdfSignature() {}

    public static boolean isPdf(InputStream input) throws IOException {
        if (!input.markSupported()) throw new IllegalArgumentException("PDF detection requires a buffered stream");
        input.mark(5);
        try {
            return "%PDF-".equals(new String(input.readNBytes(5), StandardCharsets.US_ASCII));
        } finally {
            input.reset();
        }
    }
}
