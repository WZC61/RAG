package com.yizhaoqi.smartpai.parsing.chunk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reuses the existing parsing configuration for both legacy and assembled page text. */
@Configuration(proxyBeanMethods = false)
public class TextChunkingConfiguration {
    @Bean
    public TextChunker textChunker(
            @Value("${file.parsing.chunk-size}") int chunkSize,
            @Value("${file.parsing.overlap-size:100}") int overlapSize,
            @Value("${file.parsing.min-chunk-size:100}") int minChunkSize) {
        return new TextChunker(chunkSize, overlapSize, minChunkSize);
    }

    @Bean
    public ParsedDocumentChunker parsedDocumentChunker(TextChunker textChunker) {
        return new ParsedDocumentChunker(textChunker);
    }
}
