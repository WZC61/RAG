package com.yizhaoqi.smartpai.parsing.chunk;

import com.yizhaoqi.smartpai.parsing.model.ParsedDocumentArtifacts;
import com.yizhaoqi.smartpai.parsing.model.ParsedPageContent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextChunkingConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(TextChunkingConfiguration.class);

    @Test
    void usesExistingConfigurationKeysForBothChunkers() {
        context.withPropertyValues("file.parsing.chunk-size=8", "file.parsing.overlap-size=4",
                "file.parsing.min-chunk-size=0").run(application -> {
            TextChunker chunker = application.getBean(TextChunker.class);
            ParsedDocumentChunker documentChunker = application.getBean(ParsedDocumentChunker.class);
            String text = "第一句。第二句。第三句。第四句。";
            assertEquals(List.of("第一句。第二句。", "第二句。\n\n第三句。第四句。"),
                    chunker.chunk(text).stream().map(TextChunkFragment::text).toList());
            assertEquals(chunker.chunk(2, text).stream().map(TextChunkFragment::text).toList(),
                    documentChunker.chunk(new ParsedDocumentArtifacts(
                            List.of(new ParsedPageContent(2, text)), List.of())).stream().map(TextChunk::text).toList());
        });
    }

    @Test
    void keepsLegacyDefaultsForOverlapAndMinimumSize() {
        context.withPropertyValues("file.parsing.chunk-size=512").run(application -> {
            String text = "普通短文本\n\n另一个段落";
            assertEquals(new TextChunker(512, 100, 100).chunk(1, text),
                    application.getBean(TextChunker.class).chunk(1, text));
        });
    }
}
