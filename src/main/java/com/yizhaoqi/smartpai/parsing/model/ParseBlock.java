package com.yizhaoqi.smartpai.parsing.model;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Original label/content are retained, including labels unknown to this version. */
public record ParseBlock(String blockType, String content, List<Integer> bbox,
                         Integer blockId, Integer blockOrder) {
    private static final Set<String> BODY_TYPES = Set.of(
            "doc_title", "paragraph_title", "abstract", "text", "reference");

    public ParseBlock {
        bbox = bbox == null ? List.of() : List.copyOf(bbox);
    }

    public boolean isBodyText() {
        return BODY_TYPES.contains(normalizedType());
    }

    public boolean isTitle() {
        return Set.of("doc_title", "paragraph_title").contains(normalizedType());
    }

    public boolean isImageLike() {
        return Set.of("image", "chart").contains(normalizedType());
    }

    /** A caption candidate; its text must still be checked for misclassified table titles. */
    public boolean isCaption() {
        return "figure_title".equals(normalizedType());
    }

    private String normalizedType() {
        return blockType == null ? "" : blockType.trim().toLowerCase(Locale.ROOT);
    }
}
