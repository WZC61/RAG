package com.yizhaoqi.smartpai.parsing.persistence;

import com.yizhaoqi.smartpai.parsing.model.ParsedFigureContent;

import java.util.List;

/** Prepared metadata referencing an already-uploaded object, with no temporary source URL. */
public record StoredFigureArtifact(Integer pageNumber, Integer figureIndex, String figureLabel,
                                   String imagePath, List<Integer> bbox, String caption,
                                   String ocrText, String nearbyText) {
    public StoredFigureArtifact {
        bbox = bbox == null ? List.of() : List.copyOf(bbox);
    }

    static StoredFigureArtifact from(ParsedFigureContent figure, String imagePath) {
        return new StoredFigureArtifact(figure.pageNumber(), figure.figureIndex(), figure.figureLabel(),
                imagePath, figure.bbox(), figure.caption(), figure.ocrText(), figure.nearbyText());
    }
}
