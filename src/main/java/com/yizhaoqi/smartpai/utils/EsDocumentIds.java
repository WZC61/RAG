package com.yizhaoqi.smartpai.utils;

/** Stable ES identities. Legacy callers have no content generation and use a separate namespace. */
public final class EsDocumentIds {
    private EsDocumentIds() {}

    public static String text(String fileMd5, long processingGeneration, int chunkId) {
        if (processingGeneration < 1) {
            throw new IllegalArgumentException("TEXT document requires a positive processingGeneration");
        }
        return "TEXT:" + fileMd5 + ":" + processingGeneration + ":" + chunkId;
    }

    public static String legacyText(String fileMd5, String userId, int chunkId) {
        // Preserve the user-scoped legacy identity without inventing a content generation.
        return "LEGACY_TEXT:" + fileMd5 + ":" + userId + ":" + chunkId;
    }

    public static String figure(String fileMd5, long generation, int pageNumber, int figureIndex) {
        if (fileMd5 == null || fileMd5.isBlank() || generation < 1 || pageNumber < 1 || figureIndex < 1) {
            throw new IllegalArgumentException("FIGURE document requires a valid content/page/figure identity");
        }
        return "FIGURE:" + fileMd5 + ":" + generation + ":" + pageNumber + ":" + figureIndex;
    }
}
