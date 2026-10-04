package com.yizhaoqi.smartpai.parsing.pp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

import static com.yizhaoqi.smartpai.parsing.pp.PpStructureApiException.Stage.RESULT_DECODE;

/** Unwraps the official JSONL transport; leaves page content untouched for the mapper. */
public final class PpStructureResultDecoder {
    private final ObjectMapper json;
    private final ObjectReader lineReader;

    public PpStructureResultDecoder(ObjectMapper json) {
        this.json = json;
        this.lineReader = json.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** The caller owns and closes the Reader. Download size is bounded before decoding. */
    public ArrayNode decode(Reader source, String jobId) {
        BufferedReader reader = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        ArrayNode pages = json.createArrayNode();
        int lineNumber = 0;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (Thread.currentThread().isInterrupted()) throw error(jobId, "Result decoding interrupted");
                if (line.isBlank()) continue;
                JsonNode root = lineReader.readTree(line);
                JsonNode items = root.path("result").path("layoutParsingResults");
                if (!items.isArray()) throw error(jobId, "Missing or invalid layoutParsingResults at line " + lineNumber);
                for (JsonNode page : items) {
                    if (!page.isObject()) throw error(jobId, "Invalid page object at line " + lineNumber);
                    pages.add(page);
                }
            }
        } catch (IOException e) {
            throw error(jobId, "Invalid or unreadable JSONL at line " + lineNumber);
        }
        if (pages.isEmpty()) throw error(jobId, "JSONL contains no pages");
        return pages;
    }

    private PpStructureApiException error(String jobId, String message) {
        return new PpStructureApiException(RESULT_DECODE, null, null, null, jobId, message);
    }
}
