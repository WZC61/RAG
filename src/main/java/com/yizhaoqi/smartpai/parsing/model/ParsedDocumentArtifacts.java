package com.yizhaoqi.smartpai.parsing.model;

import java.util.List;

/** In-memory business artifacts, ready for later chunking and image persistence. */
public record ParsedDocumentArtifacts(List<ParsedPageContent> pages, List<ParsedFigureContent> figures) {
    public ParsedDocumentArtifacts {
        pages = pages == null ? List.of() : List.copyOf(pages);
        figures = figures == null ? List.of() : List.copyOf(figures);
    }
}
