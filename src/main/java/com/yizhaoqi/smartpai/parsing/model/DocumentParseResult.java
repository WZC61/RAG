package com.yizhaoqi.smartpai.parsing.model;

import java.util.List;

/** Stable parsing output, independent of the PP transport and persistence models. */
public record DocumentParseResult(List<PageParseResult> pages) {
    public DocumentParseResult {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /** Includes document/paragraph titles; excludes captions, tables and page decoration. */
    public List<ParseBlock> getAllTextBlocks() {
        return pages.stream().flatMap(page -> page.blocks().stream())
                .filter(ParseBlock::isBodyText).toList();
    }

    public List<FigureParseResult> getAllFigures() {
        return pages.stream().flatMap(page -> page.figures().stream()).toList();
    }
}
