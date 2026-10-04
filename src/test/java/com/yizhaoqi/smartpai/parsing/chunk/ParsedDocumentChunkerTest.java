package com.yizhaoqi.smartpai.parsing.chunk;

import com.yizhaoqi.smartpai.parsing.model.ParsedDocumentArtifacts;
import com.yizhaoqi.smartpai.parsing.model.ParsedFigureContent;
import com.yizhaoqi.smartpai.parsing.model.ParsedPageContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParsedDocumentChunkerTest {
    private final ParsedDocumentChunker chunker = new ParsedDocumentChunker(new TextChunker(8, 4, 0));

    @Test
    void singlePageRetainsPageNumber() {
        assertEquals(List.of(new TextChunk(7, 1, "正文", "正文")),
                chunker.chunk(document(new ParsedPageContent(7, "正文"))));
    }

    @Test
    void pagesAreSortedBeforeGlobalNumbering() {
        List<TextChunk> chunks = chunker.chunk(document(
                new ParsedPageContent(2, "第三句。第四句。第五句。第六句。"),
                new ParsedPageContent(1, "第一句。第二句。甲一句。乙二句。")));
        assertEquals(List.of(1, 1, 2, 2), chunks.stream().map(TextChunk::pageNumber).toList());
        assertEquals(List.of(1, 2, 3, 4), chunks.stream().map(TextChunk::chunkIndex).toList());
        assertEquals("第一句。第二句。", chunks.get(0).text());
        assertEquals("第三句。第四句。", chunks.get(2).text());
    }

    @Test
    void overlapRemainsInsideEachPage() {
        List<TextChunk> chunks = chunker.chunk(document(
                new ParsedPageContent(1, "第一句。第二句。第三句。第四句。"),
                new ParsedPageContent(2, "第五句。第六句。第七句。第八句。")));
        assertEquals(List.of("第一句。第二句。", "第二句。\n\n第三句。第四句。",
                        "第五句。第六句。", "第六句。\n\n第七句。第八句。"),
                chunks.stream().map(TextChunk::text).toList());
    }

    @Test
    void emptyPagesDoNotConsumeChunkIndices() {
        List<TextChunk> chunks = chunker.chunk(document(new ParsedPageContent(1, ""),
                new ParsedPageContent(2, "正文"), new ParsedPageContent(3, " \n\t"),
                new ParsedPageContent(4, null), new ParsedPageContent(5, "末页")));
        assertEquals(List.of(1, 2), chunks.stream().map(TextChunk::chunkIndex).toList());
        assertEquals(List.of(2, 5), chunks.stream().map(TextChunk::pageNumber).toList());
    }

    @Test
    void anchorsAreAlreadyPresentBeforeAnyPersistence() {
        ParsedDocumentChunker large = new ParsedDocumentChunker(new TextChunker(512, 0, 0));
        List<TextChunk> chunks = large.chunk(document(new ParsedPageContent(1, "甲".repeat(121))));
        assertEquals("甲".repeat(120) + "…", chunks.get(0).anchorText());
    }

    @Test
    void figuresDoNotChangeTextResults() {
        ParsedDocumentArtifacts base = document(new ParsedPageContent(1, "正文"));
        ParsedDocumentArtifacts withFigure = new ParsedDocumentArtifacts(base.pages(), List.of(figure()));
        assertEquals(chunker.chunk(base), chunker.chunk(withFigure));
    }

    @Test
    void figureOnlyDocumentWithEmptyPageHasNoTextChunks() {
        assertTrue(chunker.chunk(new ParsedDocumentArtifacts(
                List.of(new ParsedPageContent(1, "")), List.of(figure()))).isEmpty());
    }

    @Test
    void figuresWithoutPagesHaveNoTextChunks() {
        assertTrue(chunker.chunk(new ParsedDocumentArtifacts(List.of(), List.of(figure()))).isEmpty());
    }

    @Test
    void emptyDocumentHasNoChunks() {
        assertTrue(chunker.chunk(new ParsedDocumentArtifacts(null, null)).isEmpty());
    }

    @Test
    void repeatedCallsRestartNumberingAndAreStable() {
        ParsedDocumentArtifacts artifacts = document(new ParsedPageContent(2, "第二页"), new ParsedPageContent(1, "第一页"));
        List<TextChunk> first = chunker.chunk(artifacts);
        assertEquals(first, chunker.chunk(artifacts));
        assertEquals(1, first.get(0).chunkIndex());
        assertEquals(List.of(2, 1), artifacts.pages().stream().map(ParsedPageContent::pageNumber).toList());
    }

    private static ParsedDocumentArtifacts document(ParsedPageContent... pages) {
        return new ParsedDocumentArtifacts(List.of(pages), List.of());
    }

    private static ParsedFigureContent figure() {
        return new ParsedFigureContent(1, 1, "Figure 1", List.of(1, 2, 3, 4),
                "caption", "OCR", "nearby", "image-key", "https://example.invalid/figure.png");
    }
}
