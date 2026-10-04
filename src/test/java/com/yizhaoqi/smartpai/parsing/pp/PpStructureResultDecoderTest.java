package com.yizhaoqi.smartpai.parsing.pp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;

import static com.yizhaoqi.smartpai.parsing.pp.PpStructureApiException.Stage.RESULT_DECODE;
import static org.junit.jupiter.api.Assertions.*;

class PpStructureResultDecoderTest {
    private final PpStructureResultDecoder decoder = new PpStructureResultDecoder(new ObjectMapper());

    @Test
    void singlePageRetainsUnknownRawFields() {
        var pages = decoder.decode(new StringReader("{\"result\":{\"layoutParsingResults\":[{\"future\":42}]}}"), "job");
        assertEquals(1, pages.size());
        assertEquals(42, pages.get(0).path("future").intValue());
    }

    @Test
    void oneLineMayContainMultiplePages() {
        var pages = decoder.decode(new StringReader(line(1, 2)), "job");
        assertEquals(2, pages.size());
        assertEquals(1, pages.get(0).path("pageNumber").intValue());
        assertEquals(2, pages.get(1).path("pageNumber").intValue());
    }

    @Test
    void multipleLinesAndBlankLinesPreservePageOrder() {
        var pages = decoder.decode(new StringReader("\r\n" + line(3, 4) + "\r\n \n" + line(1, 2)), "job");
        assertEquals(4, pages.size());
        assertEquals(3, pages.get(0).path("pageNumber").intValue());
        assertEquals(4, pages.get(1).path("pageNumber").intValue());
        assertEquals(1, pages.get(2).path("pageNumber").intValue());
        assertEquals(2, pages.get(3).path("pageNumber").intValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\r\n", "{", "null", "{}", "{\"result\":{}}",
            "{\"result\":{\"layoutParsingResults\":{}}}",
            "{\"result\":{\"layoutParsingResults\":[]}}",
            "{\"result\":{\"layoutParsingResults\":[null]}}",
            "{\"result\":{\"layoutParsingResults\":[1]}}",
            "{\"result\":{\"layoutParsingResults\":[{}]}} {}"})
    void invalidOrEmptyResultsNeverSucceed(String input) {
        var error = assertThrows(PpStructureApiException.class, () -> decoder.decode(new StringReader(input), "job"));
        assertEquals(RESULT_DECODE, error.getStage());
        assertEquals("job", error.getJobId());
        assertNull(error.getCause());
    }

    @Test
    void badLaterLineDoesNotReturnPartialPages() {
        assertThrows(PpStructureApiException.class,
                () -> decoder.decode(new StringReader(line(1, 2) + "\n{}"), "job"));
    }

    private String line(int first, int second) {
        return "{\"result\":{\"layoutParsingResults\":[{\"pageNumber\":" + first
                + "},{\"pageNumber\":" + second + "}]}}";
    }
}
