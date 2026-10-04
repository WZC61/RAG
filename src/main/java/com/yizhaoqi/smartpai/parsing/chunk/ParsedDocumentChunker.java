package com.yizhaoqi.smartpai.parsing.chunk;

import com.yizhaoqi.smartpai.parsing.model.ParsedDocumentArtifacts;
import com.yizhaoqi.smartpai.parsing.model.ParsedPageContent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure in-memory page orchestration. Figures do not participate in text chunking. */
public final class ParsedDocumentChunker {
    private final TextChunker textChunker;

    public ParsedDocumentChunker(TextChunker textChunker) {
        this.textChunker = Objects.requireNonNull(textChunker, "textChunker");
    }

    public List<TextChunk> chunk(ParsedDocumentArtifacts artifacts) {
        Objects.requireNonNull(artifacts, "artifacts");
        List<ParsedPageContent> pages = artifacts.pages().stream()
                .sorted(Comparator.comparing(ParsedPageContent::pageNumber))
                .toList();
        List<TextChunk> chunks = new ArrayList<>();
        for (ParsedPageContent page : pages) {
            // A separate call per page prevents overlap from crossing page boundaries.
            for (TextChunkFragment fragment : textChunker.chunk(page.pageNumber(), page.text())) {
                chunks.add(new TextChunk(fragment.pageNumber(), chunks.size() + 1,
                        fragment.text(), fragment.anchorText()));
            }
        }
        return List.copyOf(chunks);
    }
}
