package com.yizhaoqi.smartpai.parsing.model;

import java.util.List;

/** Source URL may expire and is not a persistent imagePath. Description is a later stage. */
public record FigureParseResult(Integer pageNumber, Integer figureIndex, String figureLabel,
                                List<Integer> bbox, String caption, String ocrText,
                                String nearbyText, String sourceImageKey, String sourceImageUrl) {
    public FigureParseResult {
        bbox = bbox == null ? List.of() : List.copyOf(bbox);
    }
}
