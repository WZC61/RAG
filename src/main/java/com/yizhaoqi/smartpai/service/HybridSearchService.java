package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.*;
import com.yizhaoqi.smartpai.exception.RetrievalException;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class HybridSearchService {
    public static final int MAX_TOP_K = 20;
    public static final int MAX_CANDIDATES = 100;
    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);
    @Autowired private ElasticsearchClient esClient;
    @Autowired private EmbeddingClient embeddingClient;
    @Autowired private UserRepository userRepository;
    @Autowired private OrgTagCacheService orgTagCacheService;
    @Autowired private FileUploadRepository fileUploadRepository;
    @Autowired private FileContentRepository fileContentRepository;
    private final RrfFusion fusion = new RrfFusion();

    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        return retrieveWithPermission(query, userId, topK).toSearchResults();
    }

    /** Compatibility entry point remains public-only. */
    public List<SearchResult> search(String query, int topK) {
        return retrieve(query, topK).toSearchResults();
    }

    public RetrievalResponse retrieveWithPermission(String query, String userId, int topK) {
        validate(query, topK);
        try {
            User user = resolveUser(userId);
            List<String> tags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            return searchScoped(query.trim(), user.getId().toString(), tags, user.getId().toString(), topK);
        } catch (RetrievalException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RetrievalException("Cannot resolve retrieval permissions or content scope", failure);
        }
    }

    public RetrievalResponse retrieve(String query, int topK) {
        validate(query, topK);
        return searchScoped(query.trim(), null, List.of(), "system", topK);
    }

    private void validate(String query, int topK) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
        if (topK < 1) throw new IllegalArgumentException("topK must be positive");
    }

    private User resolveUser(String identity) {
        if (identity == null || identity.isBlank()) throw new RetrievalException("User identity is required");
        try {
            return userRepository.findById(Long.parseLong(identity)).orElseThrow(() -> new RetrievalException("User not found"));
        } catch (NumberFormatException compatibilityUsername) {
            return userRepository.findByUsername(identity).orElseThrow(() -> new RetrievalException("User not found"));
        }
    }

    private List<String> authorizedIds(String userId, List<String> tags) {
        return userId == null ? fileUploadRepository.findPublicContentIds()
                : fileUploadRepository.findAuthorizedContentIds(userId, tags);
    }

    /** ES ACL plus a DB-derived scope prevents stale ES grants from disclosing revoked content. */
    Query permissionFilter(String userId, List<String> tags, List<String> ids) {
        if (ids.isEmpty()) return Query.of(q -> q.matchNone(m -> m));
        Query acl = Query.of(q -> q.bool(b -> {
            b.minimumShouldMatch("1").should(sh -> sh.term(t -> t.field("public").value(true)));
            if (userId != null) b.should(sh -> sh.term(t -> t.field("allowedUserIds").value(userId)));
            if (!tags.isEmpty()) b.should(sh -> sh.terms(t -> t.field("allowedOrgTags")
                    .terms(v -> v.value(tags.stream().map(FieldValue::of).toList()))));
            return b;
        }));
        return Query.of(q -> q.bool(b -> b.filter(acl).filter(f -> f.terms(t -> t.field("fileMd5")
                .terms(v -> v.value(ids.stream().map(FieldValue::of).toList()))))));
    }

    private RetrievalResponse searchScoped(String query, String userId, List<String> tags, String requesterId, int requestedTopK) {
        int topK = Math.min(requestedTopK, MAX_TOP_K);
        int candidates = Math.min(MAX_CANDIDATES, Math.max(30, topK * 5));
        try {
            Map<String, Long> scope = indexedGenerations(authorizedIds(userId, tags));
            if (scope.isEmpty()) return new RetrievalResponse(List.of(), Set.of(), topK, candidates);
            Query filter = contentFilter(permissionFilter(userId, tags, new ArrayList<>(scope.keySet())), scope);
            Set<RetrievalResult.Channel> failed = EnumSet.noneOf(RetrievalResult.Channel.class);
            List<Hit<EsDocument>> vector = List.of();
            List<Hit<EsDocument>> bm25 = List.of();
            // Independent requests: failure in either branch must not prevent the other request.
            try {
                List<Float> embedding = embedToVectorList(query, requesterId);
                vector = checkedHits(esClient.search(s -> s.index("knowledge_base").size(candidates)
                        .source(source -> source.filter(f -> f.excludes("vector")))
                        .knn(k -> k.field("vector").queryVector(embedding).k(candidates)
                                .numCandidates(candidates * 5).filter(filter)), EsDocument.class));
            } catch (Exception failure) {
                failed.add(RetrievalResult.Channel.VECTOR);
                logger.warn("Vector retrieval failed ({})", failure.getClass().getSimpleName());
            }
            try {
                bm25 = checkedHits(esClient.search(s -> s.index("knowledge_base").size(candidates)
                        .source(source -> source.filter(f -> f.excludes("vector")))
                        .query(q -> q.bool(b -> b.must(m -> m.match(ma -> ma.field("textContent").query(query)))
                                .filter(filter))), EsDocument.class));
            } catch (Exception failure) {
                failed.add(RetrievalResult.Channel.BM25);
                logger.warn("BM25 retrieval failed ({})", failure.getClass().getSimpleName());
            }
            if (failed.size() == 2) throw new RetrievalException("Vector and BM25 retrieval both failed");
            Set<String> hitFiles = new HashSet<>();
            vector.forEach(h -> { if (h.source() != null) hitFiles.add(h.source().getFileMd5()); });
            bm25.forEach(h -> { if (h.source() != null) hitFiles.add(h.source().getFileMd5()); });
            // Bulk recheck after ES, including changes to generation/state and revoked relations.
            hitFiles.retainAll(authorizedIds(userId, tags));
            Map<String, Long> current = indexedGenerations(hitFiles);
            List<RetrievalResult> results = fusion.fuse(validHits(vector, scope, current), validHits(bm25, scope, current), topK);
            attachFileNames(results, userId, tags);
            return new RetrievalResponse(results, failed, topK, candidates);
        } catch (RetrievalException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RetrievalException("Retrieval permission or content scope lookup failed", failure);
        }
    }

    private List<Hit<EsDocument>> checkedHits(SearchResponse<EsDocument> response) {
        if (response.timedOut() || response.shards().failed().longValue() > 0)
            throw new RetrievalException("Elasticsearch returned incomplete retrieval results");
        return response.hits().hits();
    }

    private Map<String, Long> indexedGenerations(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return fileContentRepository.findByFileMd5InAndProcessingStatusAndDeletedAtIsNull(ids, FileContent.ProcessingStatus.INDEXED)
                .stream().filter(c -> c.getProcessingStatus() == FileContent.ProcessingStatus.INDEXED && c.getDeletedAt() == null)
                .collect(Collectors.toMap(FileContent::getFileMd5, FileContent::getProcessingGeneration));
    }

    private Query contentFilter(Query permission, Map<String, Long> generations) {
        // Group by generation to avoid one bool clause for every accessible file.
        Map<Long, List<String>> groups = new TreeMap<>();
        generations.forEach((md5, generation) -> groups.computeIfAbsent(generation, key -> new ArrayList<>()).add(md5));
        Query content = Query.of(q -> q.bool(b -> {
            b.minimumShouldMatch("1");
            groups.forEach((generation, ids) -> b.should(s -> s.bool(pair -> pair
                    .filter(f -> f.term(t -> t.field("processingGeneration").value(generation)))
                    .filter(f -> f.terms(t -> t.field("fileMd5").terms(v -> v.value(ids.stream().map(FieldValue::of).toList())))))));
            return b;
        }));
        return Query.of(q -> q.bool(b -> b.filter(permission).filter(content)));
    }

    private List<Hit<EsDocument>> validHits(List<Hit<EsDocument>> hits, Map<String, Long> initial, Map<String, Long> current) {
        return hits.stream().filter(h -> h.source() != null).filter(h -> {
            EsDocument d = h.source();
            // Missing generation is legacy data, not evidence of membership in the current generation.
            return d.getProcessingGeneration() != null && Objects.equals(initial.get(d.getFileMd5()), d.getProcessingGeneration())
                    && Objects.equals(current.get(d.getFileMd5()), d.getProcessingGeneration());
        }).toList();
    }

    private List<Float> embedToVectorList(String text, String requesterId) {
        List<float[]> vectors = embeddingClient.embed(List.of(text), requesterId, EmbeddingClient.UsageType.QUERY);
        if (vectors == null || vectors.size() != 1 || vectors.get(0) == null || vectors.get(0).length == 0)
            throw new RetrievalException("Query embedding is empty or invalid");
        List<Float> result = new ArrayList<>(vectors.get(0).length);
        for (float value : vectors.get(0)) {
            if (!Float.isFinite(value)) throw new RetrievalException("Query embedding is not finite");
            result.add(value);
        }
        return result;
    }

    private void attachFileNames(List<RetrievalResult> results, String userId, List<String> tags) {
        if (results.isEmpty()) return;
        List<String> ids = results.stream().map(RetrievalResult::getFileMd5).distinct().toList();
        Map<String, String> names = new HashMap<>();
        List<FileUpload> relations = new ArrayList<>(fileUploadRepository.findByFileMd5In(ids));
        // Prefer the caller's filename; otherwise use an accessible shared relation.
        relations.sort(Comparator.comparing(f -> !Objects.equals(f.getUserId(), userId)));
        for (FileUpload f : relations) {
            if (f.getStatus() == FileUpload.STATUS_COMPLETED && (f.isPublic() || Objects.equals(f.getUserId(), userId)
                    || (f.getOrgTag() != null && !f.getOrgTag().startsWith("PRIVATE_") && tags.contains(f.getOrgTag()))))
                names.putIfAbsent(f.getFileMd5(), f.getFileName());
        }
        results.forEach(r -> r.setFileName(names.get(r.getFileMd5())));
    }
}
