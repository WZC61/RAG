package com.yizhaoqi.smartpai.parsing.persistence;

import com.yizhaoqi.smartpai.parsing.chunk.TextChunk;

import java.util.List;

final class ArtifactValidation {
    private ArtifactValidation() {}

    static void identity(String fileMd5, long generation) {
        if (fileMd5 == null || !fileMd5.matches("[A-Za-z0-9]{1,32}") || generation < 1)
            throw new IllegalArgumentException("Invalid fileMd5 or processingGeneration");
    }

    static void figureIdentity(Integer pageNumber, Integer figureIndex) {
        if (pageNumber == null || pageNumber < 1 || figureIndex == null || figureIndex < 1)
            throw new IllegalArgumentException("Figure pageNumber and figureIndex must be positive");
    }

    static void chunks(List<TextChunk> chunks) {
        chunks(chunks, true);
    }

    static void chunks(List<TextChunk> chunks, boolean requirePageNumbers) {
        if (chunks == null) throw new IllegalArgumentException("Text chunks must not be null");
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            if (chunk == null || chunk.chunkIndex() == null || chunk.chunkIndex() != i + 1
                    || (chunk.pageNumber() == null ? requirePageNumbers : chunk.pageNumber() < 1)
                    || chunk.text() == null || chunk.text().isBlank()
                    || chunk.anchorText() == null || chunk.anchorText().isBlank())
                throw new IllegalArgumentException("Incomplete text chunk or non-contiguous document index");
        }
    }

    static String figurePathPrefix(String fileMd5, long generation, Integer pageNumber, Integer figureIndex) {
        identity(fileMd5, generation);
        figureIdentity(pageNumber, figureIndex);
        return "figures/" + fileMd5 + "/" + generation + "/page-" + pageNumber + "-figure-" + figureIndex + ".";
    }
}
