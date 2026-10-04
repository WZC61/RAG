package com.yizhaoqi.smartpai.parsing.model;

import java.util.List;

/** Source URLs are external references, not durable image paths. */
public record ParsedFigureContent(Integer pageNumber, Integer figureIndex, String figureLabel,
                                  List<Integer> bbox, String caption, String ocrText, String nearbyText,
                                  String sourceImageKey, String sourceImageUrl) {
    public ParsedFigureContent {
        bbox = bbox == null ? List.of() : List.copyOf(bbox);
    }
}
