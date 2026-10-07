package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.RetrievalResult;
import java.util.*;

/** Pure in-memory rank fusion and deliberately small evidence deduplication rules. */
public final class RrfFusion {
    public static final int RANK_CONSTANT = 60;
    private static final int MAX_TEXT_PER_PAGE = 2;

    public List<RetrievalResult> fuse(List<Hit<EsDocument>> vector, List<Hit<EsDocument>> bm25, int topK) {
        Map<String, RetrievalResult> entries = new HashMap<>();
        add(entries, vector, RetrievalResult.Channel.VECTOR);
        add(entries, bm25, RetrievalResult.Channel.BM25);
        List<RetrievalResult> ranked = entries.values().stream()
                .sorted(Comparator.comparingDouble(RetrievalResult::getRrfScore).reversed()
                        .thenComparing(RetrievalResult::getEntryId)).toList();
        List<RetrievalResult> selected = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        Map<List<Object>, Set<String>> pageTexts = new HashMap<>();
        for (RetrievalResult result : ranked) {
            if (selected.size() >= topK) break;
            // Stable business identity also protects against historical duplicate ES ids.
            String businessId = result.getDocumentType() + ":" + result.getFileMd5() + ":"
                    + result.getProcessingGeneration() + ":" + (result.getDocumentType() == EsDocument.DocumentType.FIGURE
                    ? result.getPageNumber() + ":" + result.getFigureIndex() : result.getChunkId());
            if (!identities.add(businessId)) continue;
            if (result.getDocumentType() == EsDocument.DocumentType.TEXT) {
                List<Object> page = Arrays.asList(result.getFileMd5(), result.getProcessingGeneration(), result.getPageNumber());
                Set<String> texts = pageTexts.computeIfAbsent(page, key -> new HashSet<>());
                String normalized = result.getTextContent().replaceAll("\\s+", "");
                if (texts.contains(normalized)) continue;
                // PDF pages: at most two text excerpts per page; figures remain independent evidence.
                // Unknown page numbers (e.g. Tika) do not get an artificial whole-file cap.
                if (result.getPageNumber() != null && texts.size() >= MAX_TEXT_PER_PAGE) continue;
                texts.add(normalized);
            }
            selected.add(result);
        }
        return selected;
    }

    private void add(Map<String, RetrievalResult> entries, List<Hit<EsDocument>> hits, RetrievalResult.Channel channel) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < hits.size(); i++) {
            Hit<EsDocument> hit = hits.get(i);
            if (hit.id() == null || hit.id().isBlank() || hit.source() == null || !seen.add(hit.id())) continue;
            EsDocument d = hit.source();
            if (d.getTextContent() == null || d.getTextContent().isBlank()) continue;
            EsDocument.DocumentType type = d.getDocumentType() == null ? EsDocument.DocumentType.TEXT : d.getDocumentType();
            if (type == EsDocument.DocumentType.TEXT && d.getChunkId() == null) continue;
            if (type == EsDocument.DocumentType.FIGURE && (d.getPageNumber() == null || d.getFigureIndex() == null)) continue;
            RetrievalResult r = entries.computeIfAbsent(hit.id(), id -> fromDocument(id, d, type));
            int rank = i + 1;
            if (channel == RetrievalResult.Channel.VECTOR) r.setVectorRank(rank); else r.setBm25Rank(rank);
            r.getMatchedChannels().add(channel);
            r.setRrfScore(r.getRrfScore() + 1d / (RANK_CONSTANT + rank));
        }
    }

    private RetrievalResult fromDocument(String id, EsDocument d, EsDocument.DocumentType type) {
        RetrievalResult r = new RetrievalResult();
        r.setEntryId(id); r.setDocumentType(type); r.setFileMd5(d.getFileMd5());
        r.setProcessingGeneration(d.getProcessingGeneration()); r.setPageNumber(d.getPageNumber());
        r.setTextContent(d.getTextContent()); r.setChunkId(d.getChunkId()); r.setAnchorText(d.getAnchorText());
        r.setFigureIndex(d.getFigureIndex()); r.setFigureLabel(d.getFigureLabel());
        r.setImagePath(d.getImagePath()); r.setBbox(d.getBbox()); r.setCaption(d.getCaption());
        r.setDescription(d.getDescription()); r.setOcrText(d.getOcrText()); r.setPublic(d.isPublic());
        return r;
    }
}
