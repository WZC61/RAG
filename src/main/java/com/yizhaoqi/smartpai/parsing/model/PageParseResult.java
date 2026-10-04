package com.yizhaoqi.smartpai.parsing.model;

import java.util.List;

public record PageParseResult(Integer pageNumber, List<ParseBlock> blocks,
                              List<FigureParseResult> figures) {
    public PageParseResult {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        figures = figures == null ? List.of() : List.copyOf(figures);
    }
}
