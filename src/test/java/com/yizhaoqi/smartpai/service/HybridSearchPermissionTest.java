package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.ObjectBuilder;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.exception.RetrievalException;
import com.yizhaoqi.smartpai.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.function.Function;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class HybridSearchPermissionTest {
    ElasticsearchClient es; FileUploadRepository files; EmbeddingClient embedding;
    HybridSearchService search; List<SearchRequest> requests;
    @BeforeEach void setup() throws Exception {
        es = mock(ElasticsearchClient.class); files = mock(FileUploadRepository.class); embedding = mock(EmbeddingClient.class);
        search = new HybridSearchService();
        var contents = mock(FileContentRepository.class);
        var ready = new FileContent(); ready.setFileMd5("abc"); ready.setProcessingStatus(FileContent.ProcessingStatus.INDEXED);
        var published = new FileContent(); published.setFileMd5("public-file"); published.setProcessingStatus(FileContent.ProcessingStatus.INDEXED);
        when(contents.findByFileMd5InAndProcessingStatusAndDeletedAtIsNull(anyCollection(), any()))
                .thenAnswer(call -> List.of(ready, published).stream().filter(c -> call.<Collection<String>>getArgument(0).contains(c.getFileMd5())).toList());
        ReflectionTestUtils.setField(search, "fileContentRepository", contents);
        requests = new ArrayList<>();
        var users = mock(UserRepository.class); var orgs = mock(OrgTagCacheService.class);
        var user = new User(); user.setId(2L); user.setUsername("B");
        when(users.findById(2L)).thenReturn(Optional.of(user)); when(orgs.getUserEffectiveOrgTags("B")).thenReturn(List.of("TEAM"));
        when(files.findAuthorizedContentIds("2", List.of("TEAM"))).thenReturn(List.of("abc"));
        when(files.findPublicContentIds()).thenReturn(List.of("public-file"));
        when(embedding.embed(anyList(), anyString(), any())).thenReturn(List.of(new float[]{1,2}));
        ReflectionTestUtils.setField(search, "esClient", es); ReflectionTestUtils.setField(search, "embeddingClient", embedding);
        ReflectionTestUtils.setField(search, "fileUploadRepository", files); ReflectionTestUtils.setField(search, "userRepository", users);
        ReflectionTestUtils.setField(search, "orgTagCacheService", orgs);
        when(es.search(any(SearchRequest.class), eq(EsDocument.class)))
                .thenAnswer(call -> { requests.add(call.<SearchRequest>getArgument(0)); return response("abc"); });
    }
    static SearchResponse<EsDocument> response(String md5) {
        var doc = new EsDocument(); doc.setFileMd5(md5); doc.setTextContent("body"); doc.setChunkId(1); doc.setProcessingGeneration(1L);
        return SearchResponse.of(b -> b.took(1).timedOut(false).shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(List.of(Hit.of(hit -> hit.index("knowledge_base").id("id").score(1d).source(doc))))));
    }
    @Test void knnAndBm25UseExactlyTheSameAggregateAclAndDatabaseScope() {
        assertEquals(1, search.searchWithPermission("body", "2", 3).size()); var r = requests.get(0);
        assertEquals(requests.get(1).query().bool().filter().get(0), r.knn().get(0).filter().get(0));
        String json = r.knn().get(0).filter().get(0).toString();
        assertTrue(json.contains("allowedUserIds")); assertTrue(json.contains("allowedOrgTags"));
        assertTrue(json.contains("public")); assertTrue(json.contains("fileMd5")); assertTrue(json.contains("abc"));
        assertTrue(json.contains("minimum_should_match")); assertFalse(json.contains("\"userId\"")); assertFalse(json.contains("\"orgTag\""));
    }
    @Test void failedEmbeddingBm25KeepsPermissions() {
        when(embedding.embed(anyList(), anyString(), any())).thenThrow(new IllegalStateException("unavailable"));
        assertEquals(1, search.searchWithPermission("body", "2", 2).size());
        assertTrue(requests.get(0).knn().isEmpty()); assertTrue(requests.get(0).query().bool().filter().get(0).toString().contains("allowedUserIds"));
    }
    @Test void failedVectorHasTheSamePermissions() throws Exception {
        when(es.search(any(SearchRequest.class), eq(EsDocument.class)))
                .thenAnswer(call -> { requests.add(call.<SearchRequest>getArgument(0));
                    if (requests.size() == 1) throw new IOException("ES KNN down"); return response("abc"); });
        assertEquals(1, search.searchWithPermission("body", "2", 2).size()); assertEquals(2, requests.size());
        assertEquals(requests.get(0).knn().get(0).filter().get(0), requests.get(1).query().bool().filter().get(0));
    }
    @Test void publicCompatibilitySearchFiltersBothBranches() throws Exception {
        when(es.search(any(SearchRequest.class), eq(EsDocument.class)))
                .thenAnswer(call -> { requests.add(call.<SearchRequest>getArgument(0)); return response("public-file"); });
        assertEquals(1, search.search("body", 2).size()); var r = requests.get(0);
        assertEquals(requests.get(1).query().bool().filter().get(0), r.knn().get(0).filter().get(0));
        String filter = r.knn().get(0).filter().get(0).toString(); assertTrue(filter.contains("public")); assertFalse(filter.contains("allowedUserIds"));
    }
    @Test void publicBm25FallbackAlsoRequiresPublic() {
        when(embedding.embed(anyList(), anyString(), any())).thenReturn(List.of()); search.search("body", 2);
        String filter = requests.get(0).query().bool().filter().get(0).toString(); assertTrue(filter.contains("public-file")); assertTrue(filter.contains("public"));
    }
    @Test void revokedRelationDuringSearchCannotReturnStaleEsGrant() {
        when(files.findAuthorizedContentIds("2", List.of("TEAM"))).thenReturn(List.of("abc"), List.of());
        assertTrue(search.searchWithPermission("body", "2", 2).isEmpty());
    }
    @Test void esHitOutsideDatabaseScopeIsRejected() {
        when(files.findAuthorizedContentIds("2", List.of("TEAM"))).thenReturn(List.of("different"));
        assertTrue(search.searchWithPermission("body", "2", 2).isEmpty());
    }
    @Test void noGrantedFilesDoesNotCallEitherBranch() {
        when(files.findAuthorizedContentIds("2", List.of("TEAM"))).thenReturn(List.of()); search.searchWithPermission("body", "2", 2);
        assertTrue(requests.isEmpty()); verifyNoInteractions(embedding);
    }
    @Test void databaseAuthorizationFailureDoesNotFallBackToUnfilteredSearch() {
        when(files.findAuthorizedContentIds("2", List.of("TEAM"))).thenThrow(new IllegalStateException("DB down"));
        assertThrows(RetrievalException.class, () -> search.searchWithPermission("body", "2", 2)); verifyNoInteractions(es);
    }
}
