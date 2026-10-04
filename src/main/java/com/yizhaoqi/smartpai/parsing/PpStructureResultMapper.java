package com.yizhaoqi.smartpai.parsing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.parsing.model.DocumentParseResult;
import com.yizhaoqi.smartpai.parsing.model.FigureParseResult;
import com.yizhaoqi.smartpai.parsing.model.PageParseResult;
import com.yizhaoqi.smartpai.parsing.model.ParseBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure PP-StructureV3 JSON conversion. No Spring bean, IO, chunking or persistence. */
public final class PpStructureResultMapper {
    private static final Pattern IMAGE_SRC = Pattern.compile(
            "<img\\b[^>]*?\\s+src\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s>]+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_BOX_KEY = Pattern.compile(
            "(?:^|/)img_in_(image|chart)_box_(-?\\d+)_(-?\\d+)_(-?\\d+)_(-?\\d+)(?=\\.|$)");
    private static final Pattern TABLE_CAPTION = Pattern.compile("^(?:Table(?=\\b|\\d)|表)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIGURE_PREFIX = Pattern.compile(
            "^(?:Figure(?=\\b|\\d)|Fig\\.?(?=\\s|\\d|[.:：]|$)|图)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIGURE_LABEL = Pattern.compile(
            "^(?:Figure\\s*|Fig\\.?\\s*|图\\s*)\\d+[A-Za-z]?(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);
    // A candidate must overlap at least one quarter of the narrower box horizontally.
    private static final double MIN_HORIZONTAL_OVERLAP = 0.25;
    private final ObjectMapper jsonMapper;

    public PpStructureResultMapper() {
        this(new ObjectMapper());
    }

    public PpStructureResultMapper(ObjectMapper jsonMapper) {
        this.jsonMapper = java.util.Objects.requireNonNull(jsonMapper);
    }

    public DocumentParseResult map(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("PP response must not be blank");
        return map(jsonMapper.readTree(json));
    }

    public DocumentParseResult map(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) return new DocumentParseResult(List.of());
        if (!root.isArray()) throw new IllegalArgumentException("PP response must be a page array");
        List<PageParseResult> pages = new ArrayList<>();
        for (int index = 0; index < root.size(); index++) {
            JsonNode page = root.path(index);
            if (!page.isObject() || !page.path("prunedResult").isObject()
                    || !page.path("prunedResult").path("parsing_res_list").isArray())
                throw new IllegalArgumentException("Invalid PP page structure at page " + (index + 1));
            Integer explicitPage = integer(page.path("pageNumber"));
            if (explicitPage == null) explicitPage = integer(page.path("prunedResult").path("pageNumber"));
            int pageNumber = explicitPage != null && explicitPage > 0 ? explicitPage : index + 1;
            List<ParseBlock> blocks = readBlocks(page.path("prunedResult").path("parsing_res_list"));
            Map<String, String> images = readImages(page.path("markdown").path("images"));
            List<FigureParseResult> figures = readFigures(pageNumber, blocks, images,
                    page.path("prunedResult").path("overall_ocr_res"));
            pages.add(new PageParseResult(pageNumber, blocks, figures));
        }
        return new DocumentParseResult(pages);
    }

    private List<ParseBlock> readBlocks(JsonNode nodes) {
        List<ParseBlock> blocks = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (!node.isObject() || !node.path("block_label").isTextual()
                    || node.path("block_label").textValue().isBlank()
                    || (!node.path("block_content").isMissingNode() && !node.path("block_content").isNull()
                    && !node.path("block_content").isTextual()))
                throw new IllegalArgumentException("Invalid PP parsing block structure");
            ParseBlock block = new ParseBlock(text(node.path("block_label")), text(node.path("block_content")),
                    bbox(node.path("block_bbox")), integer(node.path("block_id")), integer(node.path("block_order")));
            if (block.isBodyText() && block.content() == null)
                throw new IllegalArgumentException("PP body block is missing text content");
            blocks.add(block);
        }
        // Stable sort retains source order for equal/missing orders, common for images/captions.
        blocks.sort(Comparator.comparing(ParseBlock::blockOrder, Comparator.nullsLast(Comparator.naturalOrder())));
        return blocks;
    }

    private Map<String, String> readImages(JsonNode node) {
        Map<String, String> images = new LinkedHashMap<>();
        if (node.isObject()) node.fields().forEachRemaining(entry ->
                images.put(entry.getKey(), nullIfBlank(text(entry.getValue()))));
        return images;
    }

    private List<FigureParseResult> readFigures(int pageNumber, List<ParseBlock> blocks,
                                               Map<String, String> images, JsonNode ocr) {
        List<ParseBlock> imageBlocks = blocks.stream().filter(ParseBlock::isImageLike).toList();
        List<CaptionMatch> matches = new ArrayList<>();
        for (int figure = 0; figure < imageBlocks.size(); figure++) {
            ParseBlock image = imageBlocks.get(figure);
            for (int caption = 0; caption < blocks.size(); caption++) {
                ParseBlock candidate = blocks.get(caption);
                String captionText = plainCaption(candidate.content());
                if (!candidate.isCaption() || captionText.isEmpty() || TABLE_CAPTION.matcher(captionText).find()
                        || image.bbox().isEmpty() || candidate.bbox().isEmpty()) continue;
                double overlap = horizontalOverlap(image.bbox(), candidate.bbox());
                if (overlap < MIN_HORIZONTAL_OVERLAP) continue; // No nearest-box-only fallback across columns.
                boolean below = candidate.bbox().get(1) >= image.bbox().get(3);
                boolean above = candidate.bbox().get(3) <= image.bbox().get(1);
                if (!below && !above) continue; // Caption must sit outside the figure vertically.
                double gap = below ? (double) candidate.bbox().get(1) - image.bbox().get(3)
                        : (double) image.bbox().get(1) - candidate.bbox().get(3);
                // Do not steal a distant title belonging to another figure further down the page.
                double maxGap = Math.max(100, image.bbox().get(3) - (double) image.bbox().get(1));
                if (gap > maxGap) continue;
                matches.add(new CaptionMatch(figure, caption, FIGURE_PREFIX.matcher(captionText).find(),
                        gap, below, overlap, captionText));
            }
        }
        // Evaluate page-wide, so a figure processed first cannot take another's closest caption.
        matches.sort(Comparator.comparing(CaptionMatch::recognizedFigure).reversed()
                .thenComparingDouble(CaptionMatch::gap)
                .thenComparing(match -> !match.below())
                .thenComparing(Comparator.comparingDouble(CaptionMatch::overlap).reversed())
                .thenComparingInt(CaptionMatch::figure).thenComparingInt(CaptionMatch::caption));
        Map<Integer, String> captions = new LinkedHashMap<>();
        Set<Integer> usedCaptions = new HashSet<>();
        for (CaptionMatch match : matches) {
            if (!captions.containsKey(match.figure()) && usedCaptions.add(match.caption())) {
                captions.put(match.figure(), match.text());
            }
        }

        List<FigureParseResult> figures = new ArrayList<>();
        for (int index = 0; index < imageBlocks.size(); index++) {
            ParseBlock image = imageBlocks.get(index);
            ImageResource resource = imageResource(image, images);
            String caption = captions.get(index);
            Matcher label = FIGURE_LABEL.matcher(caption == null ? "" : caption);
            // TODO: associate nearbyText during subsequent body-text assembly; no chunking here.
            figures.add(new FigureParseResult(pageNumber, index + 1, label.find() ? label.group() : null,
                    image.bbox(), caption, figureOcr(ocr, image.bbox()), null, resource.key(), resource.url()));
        }
        return figures;
    }

    private ImageResource imageResource(ParseBlock image, Map<String, String> images) {
        Matcher src = IMAGE_SRC.matcher(image.content() == null ? "" : image.content());
        if (src.find()) {
            String key = src.group(1) != null ? src.group(1) : src.group(2) != null ? src.group(2) : src.group(3);
            key = key.replace("&amp;", "&").trim();
            if (key.contains("img_in_table_box_") || key.contains("img_in_formula_box_")) return new ImageResource(null, null);
            return new ImageResource(key, images.get(key));
        }
        if (!image.bbox().isEmpty()) {
            for (Map.Entry<String, String> entry : images.entrySet()) {
                Matcher key = IMAGE_BOX_KEY.matcher(entry.getKey());
                if (!key.find() || !key.group(1).equals(image.blockType().trim().toLowerCase(Locale.ROOT))) continue;
                try {
                    List<Integer> coordinates = List.of(Integer.valueOf(key.group(2)), Integer.valueOf(key.group(3)),
                            Integer.valueOf(key.group(4)), Integer.valueOf(key.group(5)));
                    if (coordinates.equals(image.bbox())) return new ImageResource(entry.getKey(), entry.getValue());
                } catch (NumberFormatException ignored) {
                    // Invalid external filename coordinates do not invalidate the whole document.
                }
            }
        }
        return new ImageResource(null, null);
    }

    /** Minimal OCR association: original line order, center point inside the figure, no layout inference. */
    private String figureOcr(JsonNode ocr, List<Integer> figure) {
        JsonNode texts = ocr.path("rec_texts");
        JsonNode boxes = ocr.path("rec_boxes");
        if (figure.isEmpty() || !texts.isArray() || !boxes.isArray()) return null;
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < Math.min(texts.size(), boxes.size()); index++) {
            List<Integer> box = bbox(boxes.path(index));
            String line = nullIfBlank(text(texts.path(index)));
            if (box.isEmpty() || line == null) continue;
            double x = (box.get(0).doubleValue() + box.get(2)) / 2;
            double y = (box.get(1).doubleValue() + box.get(3)) / 2;
            if (x >= figure.get(0) && x <= figure.get(2) && y >= figure.get(1) && y <= figure.get(3)) lines.add(line);
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private double horizontalOverlap(List<Integer> first, List<Integer> second) {
        double width = Math.min(first.get(2).doubleValue(), second.get(2)) - Math.max(first.get(0), second.get(0));
        double narrower = Math.min(first.get(2).doubleValue() - first.get(0), second.get(2).doubleValue() - second.get(0));
        return Math.max(0, width) / narrower;
    }

    private List<Integer> bbox(JsonNode node) {
        if (!node.isArray() || node.size() != 4) return List.of();
        List<Integer> coordinates = new ArrayList<>();
        for (JsonNode value : node) {
            Integer coordinate = integer(value);
            if (coordinate == null) return List.of();
            coordinates.add(coordinate);
        }
        if (coordinates.get(2) <= coordinates.get(0) || coordinates.get(3) <= coordinates.get(1)) return List.of();
        return coordinates;
    }

    private Integer integer(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : null;
    }

    private String text(JsonNode node) {
        return node.isTextual() ? node.textValue() : null;
    }

    private String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String plainCaption(String content) {
        if (content == null) return "";
        return content.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    private record ImageResource(String key, String url) { }
    private record CaptionMatch(int figure, int caption, boolean recognizedFigure,
                                double gap, boolean below, double overlap, String text) { }
}
