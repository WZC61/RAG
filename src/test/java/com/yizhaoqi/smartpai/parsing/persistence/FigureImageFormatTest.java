package com.yizhaoqi.smartpai.parsing.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FigureImageFormatTest {
    @Test
    void jpegAndPngRetainExistingSignatureRecognition() throws IOException {
        assertEquals("image/jpeg", FigureImageFormat.detect(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}));
        assertEquals("image/png", FigureImageFormat.detect(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VP8 ", "VP8L", "VP8X"})
    void allThreeExistingWebpVariantsRemainSupported(String chunk) throws IOException {
        byte[] bytes = ("RIFFxxxxWEBP" + chunk).getBytes(StandardCharsets.US_ASCII);
        assertEquals("image/webp", FigureImageFormat.detect(bytes));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "GIF89a", "BMxxx", "<svg/>", "<html/>", "{\"error\":1}", "RIFFxxxxWAVEfmt ", "RIFFxxxxWEBP", "RIFFxxxxWEBPVP8?"})
    void unsupportedAndTruncatedSignaturesFail(String body) {
        assertThrows(IOException.class, () -> FigureImageFormat.detect(body.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void truncatedJpegAndPngFail() {
        assertThrows(IOException.class, () -> FigureImageFormat.detect(new byte[]{(byte) 0xff, (byte) 0xd8}));
        assertThrows(IOException.class, () -> FigureImageFormat.detect(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47}));
        assertThrows(IOException.class, () -> FigureImageFormat.detect(null));
    }
}
