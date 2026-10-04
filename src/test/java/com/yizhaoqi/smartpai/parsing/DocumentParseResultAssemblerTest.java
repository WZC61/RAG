package com.yizhaoqi.smartpai.parsing;

import com.yizhaoqi.smartpai.parsing.model.DocumentParseResult;
import com.yizhaoqi.smartpai.parsing.model.FigureParseResult;
import com.yizhaoqi.smartpai.parsing.model.PageParseResult;
import com.yizhaoqi.smartpai.parsing.model.ParseBlock;
import com.yizhaoqi.smartpai.parsing.model.ParsedDocumentArtifacts;
import com.yizhaoqi.smartpai.parsing.model.ParsedFigureContent;
import com.yizhaoqi.smartpai.parsing.model.ParsedPageContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParseResultAssemblerTest {
    private static final List<Integer> FIGURE_BOX = List.of(0, 50, 100, 100);
    private final DocumentParseResultAssembler assembler = new DocumentParseResultAssembler();

    @Test
    void realFixtureFlowsThroughMapperAndPreservesFigureAssociations() throws Exception {
        DocumentParseResult parsed;
        try (var input = getClass().getResourceAsStream("/pp-structure/pp-structure-sample.json")) {
            assertNotNull(input);
            parsed = new PpStructureResultMapper().map(new com.fasterxml.jackson.databind.ObjectMapper().readTree(input));
        }
        ParsedDocumentArtifacts artifacts = assembler.assemble(parsed);
        assertEquals(5, artifacts.pages().size());
        assertEquals(List.of(1, 2, 3, 4, 5), artifacts.pages().stream().map(ParsedPageContent::pageNumber).toList());
        assertEquals(4, artifacts.figures().size());
        assertTrue(artifacts.pages().stream().anyMatch(p -> !p.text().isBlank()));
        assertTrue(artifacts.figures().stream().anyMatch(f -> f.nearbyText() != null));
        for (int i = 0; i < artifacts.figures().size(); i++) {
            var original = parsed.getAllFigures().get(i);
            var actual = artifacts.figures().get(i);
            assertEquals(original.pageNumber(), actual.pageNumber());
            assertEquals(original.figureIndex(), actual.figureIndex());
            assertEquals(original.figureLabel(), actual.figureLabel());
            assertEquals(original.bbox(), actual.bbox());
            assertEquals(original.caption(), actual.caption());
            assertEquals(original.ocrText(), actual.ocrText());
            assertEquals(original.sourceImageKey(), actual.sourceImageKey());
            assertEquals(original.sourceImageUrl(), actual.sourceImageUrl());
            assertNull(original.nearbyText()); // input remains untouched
        }
        for (int i = 0; i < parsed.pages().size(); i++) {
            String text = artifacts.pages().get(i).text();
            for (var block : parsed.pages().get(i).blocks()) {
                if (List.of("header", "footer", "number", "figure_title").contains(block.blockType())
                        && block.content() != null && !block.content().isBlank()) {
                    assertFalse(text.contains(block.content().strip()));
                }
            }
        }
    }

    @Test
    void sortsBodyByNumericOrderAndPreservesEqualAndMissingOrders() {
        var blocks = List.of(body(4, " fourth ", 0), body(null, "null first", 0), body(2, "second A", 0),
                body(1, "first", 0), body(2, "second B", 0), body(null, "null second", 0));
        var result = assemble(blocks);
        assertEquals("first\n\nsecond A\n\nsecond B\n\nfourth\n\nnull first\n\nnull second", result.pages().get(0).text());
        assertEquals(4, blocks.get(0).blockOrder());
    }

    @ParameterizedTest
    @ValueSource(strings = {"doc_title", "paragraph_title", "abstract", "text", "reference", "table", "formula"})
    void retainsKnowledgeCandidatesWithOnlyOuterWhitespaceRemoved(String type) {
        var result = assemble(List.of(block(type, 1, "  First line\n  Second line  \n", List.of())));
        assertEquals("First line\n  Second line", result.pages().get(0).text());
    }

    @Test
    void preservesRawTableMarkdownAndFormulaLatex() {
        String table = "| A | B |\n|---|---|\n| 1 | 2 |";
        String formula = "$$\\alpha + \\beta = 1$$";
        var result = assemble(List.of(block("table", 1, table, List.of()), block("formula", 2, formula, List.of())));
        assertEquals(table + "\n\n" + formula, result.pages().get(0).text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "chart", "figure_title", "number", "header", "footer", "header_image",
            "footer_image", "footnote", "unknown_layout_helper", "seal", ""})
    void excludesDecorationImagesCaptionsAndUnknownTypes(String type) {
        var result = assemble(List.of(body(1, "正文", 0), block(type, 2, "EXCLUDED", List.of())));
        assertEquals("正文", result.pages().get(0).text());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void emptyKnowledgeBlocksAreSkipped(String value) {
        var result = assemble(List.of(body(1, "正文", 0), block("table", 2, value, List.of()),
                block("formula", 3, value, List.of())));
        assertEquals("正文", result.pages().get(0).text());
    }

    @Test
    void normalizesLabelCaseButLeavesBodyContentUntouched() {
        var result = assemble(List.of(block(" TEXT ", 1, "中文  spacing", List.of()),
                block(" FORMULA ", 2, "x = y", List.of())));
        assertEquals("中文  spacing\n\nx = y", result.pages().get(0).text());
    }

    @Test
    void figureGetsNearestOrderedBodyOnEachSideSkippingCaptionAndDecoration() {
        var blocks = List.of(body(1, "distant before", 0), body(2, "near before", 10), image(4, FIGURE_BOX),
                block("figure_title", 3, "caption before", List.of()),
                block("figure_title", 5, "caption after", List.of()),
                block("footer", 6, "footer", List.of()), body(7, "near after", 120), body(8, "distant after", 150));
        assertEquals("near before\n\nnear after", assemble(blocks, figure(1, FIGURE_BOX, "url")).figures().get(0).nearbyText());
    }

    @Test
    void figureAtPageStartHasOnlyFollowingContext() {
        var result = assemble(List.of(image(1, FIGURE_BOX), body(2, "after", 120)), figure(1, FIGURE_BOX, "url"));
        assertEquals("after", result.figures().get(0).nearbyText());
    }

    @Test
    void figureAtPageEndHasOnlyPrecedingContext() {
        var result = assemble(List.of(body(1, "before", 0), image(2, FIGURE_BOX)), figure(1, FIGURE_BOX, "url"));
        assertEquals("before", result.figures().get(0).nearbyText());
    }

    @Test
    void multipleFiguresHaveIndependentContext() {
        var secondBox = List.of(0, 200, 100, 250);
        var result = assemble(List.of(body(1, "first before", 0), image(2, FIGURE_BOX), body(3, "middle", 120),
                        image(4, secondBox), body(5, "last after", 280)),
                figure(1, FIGURE_BOX, "one"), figure(2, secondBox, "two"));
        assertEquals("first before\n\nmiddle", result.figures().get(0).nearbyText());
        assertEquals("middle\n\nlast after", result.figures().get(1).nearbyText());
    }

    @Test
    void knownOrderWinsOverGeometricPosition() {
        var result = assemble(List.of(body(1, "ordered before", 200), image(2, FIGURE_BOX),
                body(3, "ordered after", 0)), figure(1, FIGURE_BOX, "url"));
        assertEquals("ordered before\n\nordered after", result.figures().get(0).nearbyText());
    }

    @Test
    void missingFigureOrderUsesNearestOverlappingGeometry() {
        var result = assemble(List.of(body(1, "far before", 0), body(2, "close before", 35), image(null, FIGURE_BOX),
                body(3, "close after", 110), body(4, "far after", 180),
                block("text", 5, "other column", List.of(200, 40, 300, 50))), figure(1, FIGURE_BOX, "url"));
        assertEquals("close before\n\nclose after", result.figures().get(0).nearbyText());
    }

    @Test
    void missingBodyOrdersCanFillUnavailableSidesWithGeometry() {
        var result = assemble(List.of(body(null, "before", 20), image(2, FIGURE_BOX), body(null, "after", 110)),
                figure(1, FIGURE_BOX, "url"));
        assertEquals("before\n\nafter", result.figures().get(0).nearbyText());
    }

    @Test
    void geometryNeverContradictsKnownReadingOrder() {
        var result = assemble(List.of(image(2, FIGURE_BOX), body(3, "after despite position", 0)),
                figure(1, FIGURE_BOX, "url"));
        assertEquals("after despite position", result.figures().get(0).nearbyText());
    }

    @Test
    void tablesAndFormulasAreAlsoEligibleNearbyKnowledge() {
        var result = assemble(List.of(block("table", 1, "| data |", List.of()), image(2, FIGURE_BOX),
                block("formula", 3, "$$x$$", List.of())), figure(1, FIGURE_BOX, "url"));
        assertEquals("| data |\n\n$$x$$", result.figures().get(0).nearbyText());
    }

    @Test
    void contextKeepsTailBeforeAndHeadAfterWithin400CharactersPerSide() {
        String before = "discarded prefix " + "前".repeat(400);
        String after = "后".repeat(400) + " discarded suffix";
        var result = assemble(List.of(body(1, before, 0), image(2, FIGURE_BOX), body(3, after, 110)),
                figure(1, FIGURE_BOX, "url"));
        assertEquals("前".repeat(400) + "\n\n" + "后".repeat(400), result.figures().get(0).nearbyText());
        assertTrue(result.pages().get(0).text().contains("discarded prefix")); // only nearbyText is truncated
    }

    @Test
    void unicodeContextDoesNotSplitSurrogatePairs() {
        String emoji = "😀";
        var result = assemble(List.of(body(1, "prefix" + emoji.repeat(450), 0), image(2, FIGURE_BOX),
                body(3, emoji.repeat(450) + "suffix", 110)), figure(1, FIGURE_BOX, "url"));
        assertEquals(emoji.repeat(400) + "\n\n" + emoji.repeat(400), result.figures().get(0).nearbyText());
    }

    @Test
    void figureWithoutUrlAndFigureOnlyPageArePreserved() {
        var result = assemble(List.of(image(1, FIGURE_BOX)), figure(1, FIGURE_BOX, null));
        assertEquals("", result.pages().get(0).text());
        assertEquals(1, result.figures().size());
        assertNull(result.figures().get(0).sourceImageUrl());
        assertNull(result.figures().get(0).nearbyText());
        assertEquals("Figure 1", result.figures().get(0).figureLabel());
        assertEquals("caption", result.figures().get(0).caption());
        assertEquals("OCR", result.figures().get(0).ocrText());
        assertEquals("key", result.figures().get(0).sourceImageKey());
    }

    @Test
    void blankPagesArePreservedWhenAnotherPageHasKnowledgeAndContextNeverCrossesPages() {
        var source = new DocumentParseResult(List.of(new PageParseResult(1, List.of(), List.of()),
                new PageParseResult(2, List.of(body(1, "other page", 0)), List.of()),
                new PageParseResult(3, List.of(image(1, FIGURE_BOX)), List.of(figure(1, FIGURE_BOX, null)))));
        var result = assembler.assemble(source);
        assertEquals(List.of(1, 2, 3), result.pages().stream().map(ParsedPageContent::pageNumber).toList());
        assertEquals("", result.pages().get(0).text());
        assertNull(result.figures().get(0).nearbyText());
    }

    @Test
    void invalidGeometryAndMissingOrderRetainFigureWithoutInventingContext() {
        var result = assemble(List.of(body(null, "text without box", 0), image(null, List.of())),
                figure(1, List.of(), null));
        assertNull(result.figures().get(0).nearbyText());
        assertEquals(1, result.figures().size());
    }

    @Test
    void entirelyEmptyDecorationOnlyOrUnknownOnlyDocumentsFailClearly() {
        for (var source : List.of(new DocumentParseResult(List.of()),
                new DocumentParseResult(List.of(new PageParseResult(1, List.of(), List.of()))),
                new DocumentParseResult(List.of(new PageParseResult(1,
                        List.of(block("footer", 1, "footer", List.of()), block("table", 2, " ", List.of())), List.of()))))) {
            var error = assertThrows(IllegalArgumentException.class, () -> assembler.assemble(source));
            assertTrue(error.getMessage().contains("no usable"));
        }
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(null));
    }

    @Test
    void businessModelsAreDefensiveImmutableSnapshots() {
        var pages = new ArrayList<>(List.of(new ParsedPageContent(1, "text")));
        var box = new ArrayList<>(FIGURE_BOX);
        var figure = new ParsedFigureContent(1, 1, "Figure 1", box, null, null, null, null, null);
        var figures = new ArrayList<>(List.of(figure));
        var result = new ParsedDocumentArtifacts(pages, figures);
        pages.clear(); figures.clear(); box.clear();
        assertEquals(1, result.pages().size());
        assertEquals(1, result.figures().size());
        assertEquals(FIGURE_BOX, figure.bbox());
        assertThrows(UnsupportedOperationException.class, () -> result.pages().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.figures().clear());
        assertThrows(UnsupportedOperationException.class, () -> figure.bbox().clear());
    }

    private ParsedDocumentArtifacts assemble(List<ParseBlock> blocks, FigureParseResult... figures) {
        return assembler.assemble(new DocumentParseResult(List.of(new PageParseResult(1, blocks, List.of(figures)))));
    }

    private ParseBlock body(Integer order, String content, int y) {
        return block("text", order, content, List.of(0, y, 100, y + 10));
    }

    private ParseBlock image(Integer order, List<Integer> box) {
        return block("image", order, "<img>", box);
    }

    private ParseBlock block(String type, Integer order, String content, List<Integer> box) {
        return new ParseBlock(type, content, box, order, order);
    }

    private FigureParseResult figure(int index, List<Integer> box, String url) {
        return new FigureParseResult(1, index, "Figure 1", box, "caption", "OCR", null, "key", url);
    }
}
