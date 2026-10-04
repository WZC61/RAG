package com.yizhaoqi.smartpai.parsing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.parsing.model.DocumentParseResult;
import com.yizhaoqi.smartpai.parsing.model.FigureParseResult;
import com.yizhaoqi.smartpai.parsing.model.ParseBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PpStructureResultMapperTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PpStructureResultMapper mapper = new PpStructureResultMapper(json);

    private ArrayNode fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/pp-structure/pp-structure-sample.json")) {
            assertNotNull(input);
            return (ArrayNode) json.readTree(input);
        }
    }

    private ObjectNode page(ArrayNode pages) {
        ObjectNode page = pages.addObject();
        page.putObject("prunedResult").putArray("parsing_res_list");
        page.putObject("markdown").putObject("images");
        return page;
    }

    private ObjectNode block(ObjectNode page, String type, String content, int x1, int y1, int x2, int y2) {
        ArrayNode blocks = (ArrayNode) page.path("prunedResult").path("parsing_res_list");
        ObjectNode block = blocks.addObject();
        block.put("block_label", type).put("block_content", content);
        block.putArray("block_bbox").add(x1).add(y1).add(x2).add(y2);
        block.put("block_id", blocks.size() - 1);
        return block;
    }

    private ObjectNode images(ObjectNode page) {
        return (ObjectNode) page.path("markdown").path("images");
    }

    @Test
    void realMultiPageFixtureGetsOneBasedArrayPageNumbers() throws Exception {
        DocumentParseResult result = mapper.map(fixture());
        assertEquals(List.of(1, 2, 3, 4, 5), result.pages().stream().map(p -> p.pageNumber()).toList());
        assertEquals(4, result.getAllFigures().size());
        assertTrue(result.getAllFigures().stream().allMatch(f -> f.pageNumber() != null));
    }

    @Test
    void realTextAndParagraphTitlePreserveOriginalContentAndCoordinates() throws Exception {
        ArrayNode source = fixture();
        var result = mapper.map(source);
        for (String label : List.of("text", "paragraph_title")) {
            JsonNode original = null;
            for (JsonNode node : source.path(0).path("prunedResult").path("parsing_res_list")) {
                if (label.equals(node.path("block_label").asText())) { original = node; break; }
            }
            assertNotNull(original);
            final int blockId = original.path("block_id").intValue();
            ParseBlock parsed = result.pages().get(0).blocks().stream()
                    .filter(b -> b.blockId().equals(blockId)).findFirst().orElseThrow();
            assertEquals(original.path("block_content").asText(), parsed.content());
            assertEquals(original.path("block_order").intValue(), parsed.blockOrder());
            assertEquals(original.path("block_bbox").get(0).intValue(), parsed.bbox().get(0));
            assertTrue(parsed.isBodyText());
            assertEquals("paragraph_title".equals(label), parsed.isTitle());
        }
    }

    @Test
    void blockOrderSortsNumericallyWithStableEqualAndNullOrders() {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        block(page, "text", "second", 0, 0, 10, 10).put("block_order", 2);
        block(page, "image", "null A", 0, 20, 10, 30).putNull("block_order");
        block(page, "text", "first", 0, 40, 10, 50).put("block_order", 1);
        block(page, "text", "same second", 0, 60, 10, 70).put("block_order", 2);
        block(page, "figure_title", "null B", 0, 80, 10, 90);
        assertEquals(List.of("first", "second", "same second", "null A", "null B"),
                mapper.map(pages).pages().get(0).blocks().stream().map(ParseBlock::content).toList());
    }

    @Test
    void realImageGetsItsOwnResourceAndFigureOneCaption() throws Exception {
        FigureParseResult figure = mapper.map(fixture()).pages().get(1).figures().get(0);
        assertEquals(2, figure.pageNumber()); assertEquals(1, figure.figureIndex());
        assertEquals("imgs/img_in_image_box_144_146_1045_556.jpg", figure.sourceImageKey());
        assertEquals("https://example.invalid/pp-structure/" + figure.sourceImageKey(), figure.sourceImageUrl());
        assertEquals("Figure 1", figure.figureLabel());
        assertTrue(figure.caption().startsWith("Figure 1: The overall architecture"));
        assertFalse(figure.caption().contains("<div"));
        assertEquals(List.of(144, 146, 1045, 556), figure.bbox());
    }

    @Test
    void realChartIsFigureAndNeverUsesNearbyTableTwoTitle() throws Exception {
        var page = mapper.map(fixture()).pages().get(4);
        assertEquals(1, page.figures().size());
        FigureParseResult chart = page.figures().get(0);
        assertEquals("imgs/img_in_chart_box_184_140_999_547.jpg", chart.sourceImageKey());
        assertEquals("Figure 4", chart.figureLabel());
        assertFalse(chart.caption().contains("Table 2"));
        assertTrue(page.blocks().stream().anyMatch(b -> "table".equals(b.blockType())));
    }

    @Test
    void realSideBySideFiguresHaveDistinctCaptionsAndLocalIndexes() throws Exception {
        List<FigureParseResult> figures = mapper.map(fixture()).pages().get(2).figures();
        assertEquals(2, figures.size());
        assertEquals(List.of(1, 2), figures.stream().map(FigureParseResult::figureIndex).toList());
        assertEquals(List.of("Figure 2", "Figure 3"), figures.stream().map(FigureParseResult::figureLabel).toList());
        assertTrue(figures.get(0).caption().contains("Generation Module"));
        assertTrue(figures.get(1).caption().contains("missing-type prompts"));
        assertNotEquals(figures.get(0).sourceImageKey(), figures.get(1).sourceImageKey());
    }

    @Test
    void realMisclassifiedTableOneIsPreservedAsBlockButNotFigure() throws Exception {
        var page = mapper.map(fixture()).pages().get(3);
        assertTrue(page.figures().isEmpty());
        assertTrue(page.blocks().stream().anyMatch(b -> b.isCaption() && b.content().contains("Table 1:")));
    }

    @Test
    void stringAndJsonNodeEntryPointsProduceIdenticalResults() throws Exception {
        ArrayNode source = fixture();
        assertEquals(mapper.map(source), mapper.map(json.writeValueAsString(source)));
    }

    @Test
    void explicitPositivePageNumberOverridesArrayIndex() {
        ArrayNode pages = json.createArrayNode();
        page(pages).put("pageNumber", 9);
        page(pages).put("pageNumber", 0);
        ((ObjectNode) page(pages).path("prunedResult")).put("pageNumber", 12);
        assertEquals(List.of(9, 2, 12), mapper.map(pages).pages().stream().map(p -> p.pageNumber()).toList());
    }

    @Test
    void missingImageUrlKeepsFigureAndItsSourceKey() throws Exception {
        ArrayNode source = fixture();
        ((ObjectNode) source.path(1).path("markdown")).remove("images");
        FigureParseResult figure = mapper.map(source).pages().get(1).figures().get(0);
        assertNotNull(figure.sourceImageKey()); assertNull(figure.sourceImageUrl());
        assertEquals("Figure 1", figure.figureLabel());
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "chart"})
    void missingSrcFallsBackToMatchingTypeAndExactBbox(String type) {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        block(page, type, "", 10, 20, 110, 120);
        images(page).put("imgs/img_in_table_box_10_20_110_120.jpg", "wrong table");
        images(page).put("imgs/img_in_formula_box_10_20_110_120.jpg", "wrong formula");
        images(page).put("imgs/img_in_" + type + "_box_10_20_110_121.jpg", "wrong bbox");
        String key = "imgs/img_in_" + type + "_box_10_20_110_120.jpg";
        images(page).put(key, "https://example.invalid/correct.jpg");
        FigureParseResult figure = mapper.map(pages).getAllFigures().get(0);
        assertEquals(key, figure.sourceImageKey()); assertEquals("https://example.invalid/correct.jpg", figure.sourceImageUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"table", "formula"})
    void neverAssociatesTableOrFormulaResourcesEvenWhenSrcPointsAtThem(String type) {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        String key = "imgs/img_in_" + type + "_box_0_0_100_100.jpg";
        block(page, "image", "<img src='" + key + "'>", 0, 0, 100, 100);
        block(page, "chart", "", 0, 0, 100, 100);
        images(page).put(key, "https://example.invalid/excluded.jpg");
        assertEquals(2, mapper.map(pages).getAllFigures().size());
        assertTrue(mapper.map(pages).getAllFigures().stream().allMatch(f -> f.sourceImageKey() == null && f.sourceImageUrl() == null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<img src=\"imgs/a.jpg\">", "<IMG SRC='imgs/a.jpg'>", "<img src=imgs/a.jpg>"})
    void extractsQuotedAndUnquotedImageSources(String html) {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        block(page, "image", html, 0, 0, 100, 100);
        images(page).put("imgs/a.jpg", "https://example.invalid/a.jpg");
        assertEquals("https://example.invalid/a.jpg", mapper.map(pages).getAllFigures().get(0).sourceImageUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"<div>Table 1: results</div>", "表 2：结果", "Table1: results", "table12: results"})
    void excludesEnglishAndChineseTableCaptionsEvenWhenClosest(String caption) {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        block(page, "image", "", 0, 0, 100, 100);
        block(page, "figure_title", caption, 0, 101, 100, 110);
        block(page, "figure_title", "Figure 3: correct", 0, 120, 100, 130);
        assertEquals("Figure 3", mapper.map(pages).getAllFigures().get(0).figureLabel());
    }

    @Test
    void horizontalOverlapWinsOverCloserCaptionInAnotherColumn() {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        block(page, "image", "", 0, 0, 100, 100);
        block(page, "figure_title", "Figure 9: other column", 200, 101, 300, 110);
        block(page, "figure_title", "Figure 1: same column", 0, 130, 100, 140);
        assertEquals("Figure 1", mapper.map(pages).getAllFigures().get(0).figureLabel());
    }

    @Test
    void pageWideMatchingDoesNotLetFirstFigureStealSecondFiguresCaption() {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        block(page, "image", "", 0, 0, 200, 100);
        block(page, "image", "", 100, 0, 300, 120);
        block(page, "figure_title", "Figure 2: second", 100, 125, 300, 135);
        block(page, "figure_title", "Figure 1: first", 0, 145, 200, 155);
        assertEquals(List.of("Figure 1", "Figure 2"), mapper.map(pages).getAllFigures().stream()
                .map(FigureParseResult::figureLabel).toList());
    }

    @Test
    void captionCanBeAboveAndBelowWinsWhenDistanceIsEqual() {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        block(page, "image", "", 0, 100, 100, 200);
        block(page, "figure_title", "Figure 1: above", 0, 80, 100, 90);
        assertEquals("Figure 1", mapper.map(pages).getAllFigures().get(0).figureLabel());
        block(page, "figure_title", "Figure 2: below", 0, 210, 100, 220);
        assertEquals("Figure 2", mapper.map(pages).getAllFigures().get(0).figureLabel());
    }

    @Test
    void unrelatedDistantCaptionIsNotAttached() {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        block(page, "image", "", 0, 0, 100, 100);
        block(page, "figure_title", "Figure 8: far away", 0, 500, 100, 510);
        assertNull(mapper.map(pages).getAllFigures().get(0).caption());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Figure 3", "Fig. 2", "Fig 12", "图 4", "图3"})
    void extractsFigureLabels(String label) {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        block(page, "image", "", 0, 0, 100, 100);
        block(page, "figure_title", "<div>" + label + ": caption</div>", 0, 110, 100, 130);
        assertEquals(label, mapper.map(pages).getAllFigures().get(0).figureLabel());
    }

    @Test
    void genericCaptionIsRetainedWithoutInventingFigureLabel() {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        block(page, "image", "", 0, 0, 100, 100);
        block(page, "figure_title", "Overview of the model", 0, 110, 100, 130);
        FigureParseResult figure = mapper.map(pages).getAllFigures().get(0);
        assertEquals("Overview of the model", figure.caption()); assertNull(figure.figureLabel());
    }

    @Test
    void bodyHelpersFilterDecorationButKeepRawBlocksAndUnknownLabels() {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        List<String> body = List.of("doc_title", "paragraph_title", "abstract", "text", "reference");
        List<String> others = List.of("number", "footer", "header", "footnote", "footer_image", "header_image", "future_label", "table");
        body.forEach(label -> block(page, label, label, 0, 0, 10, 10));
        others.forEach(label -> block(page, label, label, 0, 0, 10, 10));
        DocumentParseResult result = mapper.map(pages);
        assertEquals(body, result.getAllTextBlocks().stream().map(ParseBlock::blockType).toList());
        assertEquals(13, result.pages().get(0).blocks().size());
        assertTrue(result.getAllFigures().isEmpty());
        assertEquals("future_label", result.pages().get(0).blocks().get(11).blockType());
    }

    @Test
    void ocrOnlyIncludesLinesWhoseCentersAreInsideFigure() {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        block(page, "image", "", 100, 100, 200, 200);
        ObjectNode ocr = ((ObjectNode) page.path("prunedResult")).putObject("overall_ocr_res");
        ocr.putArray("rec_texts").add("outside").add("inside A").add("inside B").add("extra text without box");
        ArrayNode boxes = ocr.putArray("rec_boxes");
        boxes.addArray().add(0).add(0).add(40).add(40);
        boxes.addArray().add(90).add(100).add(150).add(130);
        boxes.addArray().add(150).add(150).add(190).add(180);
        FigureParseResult figure = mapper.map(pages).getAllFigures().get(0);
        assertEquals("inside A\ninside B", figure.ocrText());
        assertNull(figure.nearbyText()); // Deliberately deferred to text assembly, no chunking here.
    }

    @Test
    void invalidOcrBoxesAndMissingOcrDoNotBreakFigures() {
        ArrayNode pages = json.createArrayNode(); ObjectNode page = page(pages);
        block(page, "image", "", 0, 0, 100, 100);
        assertNull(mapper.map(pages).getAllFigures().get(0).ocrText());
        ObjectNode ocr = ((ObjectNode) page.path("prunedResult")).putObject("overall_ocr_res");
        ocr.putArray("rec_texts").add("bad box").add("null box").add("empty");
        ocr.putArray("rec_boxes").addArray().add(1).add(2);
        ((ArrayNode) ocr.path("rec_boxes")).addNull().addObject();
        assertNull(mapper.map(pages).getAllFigures().get(0).ocrText());
    }

    @Test
    void structurallyValidBlankPageIsAllowed() throws Exception {
        var result = mapper.map("[{\"prunedResult\":{\"parsing_res_list\":[]}}]");
        assertEquals(1, result.pages().size());
        assertTrue(result.getAllTextBlocks().isEmpty()); assertTrue(result.getAllFigures().isEmpty());
        assertTrue(result.pages().stream().allMatch(p -> p.blocks().isEmpty()));
        assertTrue(mapper.map((JsonNode) null).pages().isEmpty());
        assertTrue(mapper.map("[]").pages().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "3", "{\"prunedResult\":null}",
            "{\"prunedResult\":[]}", "{\"prunedResult\":{}}",
            "{\"prunedResult\":{\"parsing_res_list\":null}}",
            "{\"prunedResult\":{\"parsing_res_list\":{}}}",
            "{\"prunedResult\":{\"parsing_res_list\":\"text\"}}"})
    void invalidPageFailsBothAloneAndAlongsideValidText(String invalidPage) throws Exception {
        String valid = "{\"prunedResult\":{\"parsing_res_list\":[{\"block_label\":\"text\",\"block_content\":\"正文\"}]}}";
        assertThrows(IllegalArgumentException.class, () -> mapper.map("[" + invalidPage + "]"));
        assertThrows(IllegalArgumentException.class, () -> mapper.map("[" + valid + "," + invalidPage + "]"));
        assertThrows(IllegalArgumentException.class, () -> mapper.map("[" + invalidPage + "," + valid + "]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "3", "{}", "{\"block_label\":null}", "{\"block_label\":\"\"}",
            "{\"block_label\":\"text\",\"block_content\":{}}",
            "{\"block_label\":\"text\"}", "{\"block_label\":\"text\",\"block_content\":null}"})
    void malformedRequiredBlockStructureFails(String invalidBlock) {
        assertThrows(IllegalArgumentException.class, () -> mapper.map(
                "[{\"prunedResult\":{\"parsing_res_list\":[" + invalidBlock + "]}}]"));
    }

    @Test
    void malformedOptionalFieldsProduceSafeEmptyMetadata() throws Exception {
        var result = mapper.map("""
                [{"prunedResult":{"parsing_res_list":[
                  {"block_label":"image","block_content":null,"block_bbox":[0,0,0,5],"block_order":"bad"},
                  {"block_label":"chart","block_bbox":[0,0,20,"30"]}]},
                  "markdown":{"images":null}}]
                """);
        assertEquals(2, result.pages().get(0).blocks().size());
        assertEquals(2, result.getAllFigures().size());
        assertTrue(result.getAllFigures().stream().allMatch(f -> f.bbox().isEmpty() && f.sourceImageUrl() == null && f.caption() == null));
    }

    @Test
    void rejectsWrongRootAndMalformedJsonWithClearErrors() {
        assertThrows(IllegalArgumentException.class, () -> mapper.map("{}"));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(" "));
        assertThrows(JsonProcessingException.class, () -> mapper.map("[broken"));
    }

    @Test
    void internalResultsAreImmutableAndDoNotContainRawPpTrees() throws Exception {
        var result = mapper.map(fixture());
        assertThrows(UnsupportedOperationException.class, () -> result.pages().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.pages().get(0).blocks().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getAllFigures().get(0).bbox().clear());
        assertFalse(json.writeValueAsString(result).contains("overall_ocr_res"));
    }
}
