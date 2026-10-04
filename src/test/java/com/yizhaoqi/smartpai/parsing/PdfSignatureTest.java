package com.yizhaoqi.smartpai.parsing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PdfSignatureTest {
    @Test
    void readsOnlyHeaderAndRestoresPosition() throws Exception {
        byte[] bytes = "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII);
        ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
        assertTrue(PdfSignature.isPdf(stream));
        assertEquals(bytes.length, stream.available());
        assertArrayEquals(bytes, stream.readAllBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "%PDF", "plain text", "%pdf-1.7", "not%PDF-"})
    void nonPdfAndShortInputsArePreserved(String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
        assertFalse(PdfSignature.isPdf(stream));
        assertArrayEquals(bytes, stream.readAllBytes());
    }
}
