package com.yizhaoqi.smartpai.entity;

import java.util.List;
import java.util.Set;

/** Failure is an exception; successful empty retrieval and degraded empty retrieval remain distinguishable. */
public record RetrievalResponse(List<RetrievalResult> results, Set<RetrievalResult.Channel> failedChannels,
                                int topK, int candidateSize) {
    public RetrievalResponse {
        results = List.copyOf(results);
        failedChannels = Set.copyOf(failedChannels);
    }

    public boolean isDegraded() { return !failedChannels.isEmpty(); }
    public boolean isEmpty() { return results.isEmpty(); }
    public String getRetrievalMode() {
        if (failedChannels.contains(RetrievalResult.Channel.VECTOR)) return "TEXT_ONLY";
        if (failedChannels.contains(RetrievalResult.Channel.BM25)) return "VECTOR_ONLY";
        return "HYBRID";
    }

    /** Preserve existing tool/chat signatures without discarding Figure identity. */
    public List<SearchResult> toSearchResults() {
        return results.stream().map(r -> SearchResult.fromRetrieval(r, this)).toList();
    }
}
