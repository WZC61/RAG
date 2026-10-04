package com.yizhaoqi.smartpai.parsing;

import com.yizhaoqi.smartpai.parsing.model.DocumentParseResult;
import com.yizhaoqi.smartpai.parsing.model.FigureParseResult;
import com.yizhaoqi.smartpai.parsing.model.ParseBlock;
import com.yizhaoqi.smartpai.parsing.model.ParsedDocumentArtifacts;
import com.yizhaoqi.smartpai.parsing.model.ParsedFigureContent;
import com.yizhaoqi.smartpai.parsing.model.ParsedPageContent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure business assembly; no PP JSON, IO, chunking, persistence or caption matching. */
public final class DocumentParseResultAssembler {
    public static final int NEARBY_SIDE_MAX_CHARACTERS = 400;
    private static final String SEPARATOR = "\n\n";
    private static final Set<String> KNOWLEDGE_TYPES = Set.of("table", "formula");
    private static final Comparator<ParseBlock> READING_ORDER = Comparator.comparing(
            ParseBlock::blockOrder, Comparator.nullsLast(Comparator.naturalOrder()));

    public ParsedDocumentArtifacts assemble(DocumentParseResult source) {
        if (source == null) throw new IllegalArgumentException("Document parse result must not be null");
        List<ParsedPageContent> pages = new ArrayList<>();
        List<ParsedFigureContent> figures = new ArrayList<>();
        for (var page : source.pages()) {
            // Stable sorting preserves source order for equal/missing blockOrder values.
            List<ParseBlock> blocks = page.blocks().stream().sorted(READING_ORDER).toList();
            List<ParseBlock> body = blocks.stream().filter(this::hasBodyContent).toList();
            String text = String.join(SEPARATOR, body.stream().map(b -> b.content().strip()).toList());
            pages.add(new ParsedPageContent(page.pageNumber(), text));
            for (FigureParseResult figure : page.figures()) {
                String nearby = nearbyText(figure, blocks, body);
                figures.add(new ParsedFigureContent(figure.pageNumber(), figure.figureIndex(), figure.figureLabel(),
                        figure.bbox(), figure.caption(), figure.ocrText(), nearby,
                        figure.sourceImageKey(), figure.sourceImageUrl()));
            }
        }
        if (figures.isEmpty() && pages.stream().noneMatch(page -> !page.text().isBlank()))
            throw new IllegalArgumentException("Document contains no usable body text or figures");
        return new ParsedDocumentArtifacts(pages, figures);
    }

    private boolean hasBodyContent(ParseBlock block) {
        String type = block.blockType() == null ? "" : block.blockType().strip().toLowerCase(Locale.ROOT);
        return (block.isBodyText() || KNOWLEDGE_TYPES.contains(type))
                && block.content() != null && !block.content().isBlank();
    }

    private String nearbyText(FigureParseResult figure, List<ParseBlock> blocks, List<ParseBlock> body) {
        ParseBlock anchor = figureBlock(figure, blocks);
        Integer order = anchor == null ? null : anchor.blockOrder();
        ParseBlock before = null;
        ParseBlock after = null;
        if (order != null) {
            for (ParseBlock candidate : body) {
                Integer candidateOrder = candidate.blockOrder();
                if (candidateOrder == null) continue;
                if (candidateOrder < order && (before == null || candidateOrder > before.blockOrder())) before = candidate;
                if (candidateOrder > order && (after == null || candidateOrder < after.blockOrder())) after = candidate;
            }
        }
        // Geometry only fills sides unavailable from reading order. When the figure has an
        // order, fallback candidates must lack order too; never contradict a known order.
        List<ParseBlock> fallback = order == null ? body : body.stream().filter(b -> b.blockOrder() == null).toList();
        if (before == null) before = geometricNeighbour(figure.bbox(), fallback, true);
        if (after == null) after = geometricNeighbour(figure.bbox(), fallback, false);
        List<String> context = new ArrayList<>(2);
        if (before != null) context.add(truncate(before.content().strip(), true));
        if (after != null) context.add(truncate(after.content().strip(), false));
        return context.isEmpty() ? null : String.join(SEPARATOR, context);
    }

    /** Locate the block of an already-recognized figure; no figure/caption recognition here. */
    private ParseBlock figureBlock(FigureParseResult figure, List<ParseBlock> blocks) {
        List<ParseBlock> images = blocks.stream().filter(ParseBlock::isImageLike).toList();
        Integer index = figure.figureIndex();
        if (index != null && index > 0 && index <= images.size()) {
            ParseBlock indexed = images.get(index - 1);
            if (indexed.bbox().equals(figure.bbox())) return indexed;
        }
        if (!validBox(figure.bbox())) return null;
        List<ParseBlock> matches = images.stream().filter(b -> b.bbox().equals(figure.bbox())).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /** Closest vertically disjoint body block that overlaps the figure's horizontal span. */
    private ParseBlock geometricNeighbour(List<Integer> figure, List<ParseBlock> candidates, boolean before) {
        if (!validBox(figure)) return null;
        ParseBlock nearest = null;
        double bestGap = Double.POSITIVE_INFINITY;
        double bestHorizontalDistance = Double.POSITIVE_INFINITY;
        for (ParseBlock candidate : candidates) {
            List<Integer> box = candidate.bbox();
            if (!validBox(box) || Math.min(box.get(2), figure.get(2)) <= Math.max(box.get(0), figure.get(0))) continue;
            double gap = before ? (double) figure.get(1) - box.get(3) : (double) box.get(1) - figure.get(3);
            if (gap < 0) continue;
            double horizontalDistance = Math.abs((box.get(0).doubleValue() + box.get(2))
                    - (figure.get(0).doubleValue() + figure.get(2)));
            if (gap < bestGap || (gap == bestGap && horizontalDistance < bestHorizontalDistance)) {
                nearest = candidate;
                bestGap = gap;
                bestHorizontalDistance = horizontalDistance;
            }
        }
        return nearest;
    }

    private boolean validBox(List<Integer> box) {
        return box.size() == 4 && box.get(2) > box.get(0) && box.get(3) > box.get(1);
    }

    /** Keep the preceding tail/following head, without splitting Unicode surrogate pairs. */
    private String truncate(String text, boolean tail) {
        int count = text.codePointCount(0, text.length());
        if (count <= NEARBY_SIDE_MAX_CHARACTERS) return text;
        return tail ? text.substring(text.offsetByCodePoints(0, count - NEARBY_SIDE_MAX_CHARACTERS))
                : text.substring(0, text.offsetByCodePoints(0, NEARBY_SIDE_MAX_CHARACTERS));
    }
}
