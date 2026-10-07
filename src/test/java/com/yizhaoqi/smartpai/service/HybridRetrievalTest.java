package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.*;
import com.yizhaoqi.smartpai.exception.RetrievalException;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridRetrievalTest {
    HybridSearchService service;
    ElasticsearchClient es;
    EmbeddingClient embedding;
    FileContentRepository contents;
    FileUploadRepository uploads;
    UserRepository users;
    FileContent ready;
    List<SearchRequest> requests;
    List<Hit<EsDocument>> vectorHits, bm25Hits;
    boolean failVector, failBm25;
    Runnable afterSearch;

    @BeforeEach void setup() throws Exception {
        service = new HybridSearchService(); es = mock(ElasticsearchClient.class);
        embedding = mock(EmbeddingClient.class); contents = mock(FileContentRepository.class);
        uploads = mock(FileUploadRepository.class); users = mock(UserRepository.class);
        var orgs = mock(OrgTagCacheService.class);
        var user = new User(); user.setId(2L); user.setUsername("B");
        when(users.findById(2L)).thenReturn(Optional.of(user)); when(users.findByUsername("B")).thenReturn(Optional.of(user));
        when(orgs.getUserEffectiveOrgTags("B")).thenReturn(List.of("TEAM"));
        when(uploads.findAuthorizedContentIds("2", List.of("TEAM"))).thenReturn(List.of("abc"));
        when(uploads.findPublicContentIds()).thenReturn(List.of("abc"));
        ready = new FileContent(); ready.setFileMd5("abc"); ready.setProcessingStatus(FileContent.ProcessingStatus.INDEXED);
        when(contents.findByFileMd5InAndProcessingStatusAndDeletedAtIsNull(anyCollection(), any()))
                .thenAnswer(call -> call.<Collection<String>>getArgument(0).contains("abc") ? List.of(ready) : List.of());
        var file = new FileUpload(); file.setFileMd5("abc"); file.setFileName("paper.pdf"); file.setUserId("2");
        file.setStatus(FileUpload.STATUS_COMPLETED); when(uploads.findByFileMd5In(anyList())).thenReturn(List.of(file));
        when(embedding.embed(anyList(), anyString(), any())).thenReturn(List.of(new float[]{1,2}));
        ReflectionTestUtils.setField(service, "esClient", es); ReflectionTestUtils.setField(service, "embeddingClient", embedding);
        ReflectionTestUtils.setField(service, "fileContentRepository", contents); ReflectionTestUtils.setField(service, "fileUploadRepository", uploads);
        ReflectionTestUtils.setField(service, "userRepository", users); ReflectionTestUtils.setField(service, "orgTagCacheService", orgs);
        requests = new ArrayList<>(); vectorHits = List.of(hit("a",1L)); bm25Hits = vectorHits;
        when(es.search(any(SearchRequest.class), eq(EsDocument.class))).thenAnswer(call -> {
            SearchRequest request = call.getArgument(0); requests.add(request);
            boolean vector = !request.knn().isEmpty();
            if ((vector && failVector) || (!vector && failBm25)) throw new IOException("backend unavailable");
            if (afterSearch != null) afterSearch.run();
            return response(vector ? vectorHits : bm25Hits, false, 0);
        });
    }
    static Hit<EsDocument> hit(String id, Long generation) {
        EsDocument d = new EsDocument(); d.setFileMd5("abc"); d.setProcessingGeneration(generation);
        d.setDocumentType(EsDocument.DocumentType.TEXT); d.setChunkId(1); d.setPageNumber(1); d.setTextContent("body");
        return Hit.of(h -> h.id(id).index("knowledge_base").source(d).score(1d));
    }
    static SearchResponse<EsDocument> response(List<Hit<EsDocument>> hits, boolean timeout, int failures) {
        return SearchResponse.of(r -> r.took(1).timedOut(timeout).shards(s -> s.total(1).successful(1-failures).failed(failures))
                .hits(h -> h.hits(hits)));
    }
    RetrievalResponse retrieve() { return service.retrieveWithPermission("body", "2", 5); }

    @Test void independentQueriesShareAclAndGenerationFiltersWithoutRescoreOrMinScore() {
        var response = retrieve(); assertEquals(2, requests.size());
        assertNull(requests.get(0).query()); assertTrue(requests.get(1).knn().isEmpty());
        var vectorFilter = requests.get(0).knn().get(0).filter().get(0);
        assertEquals(vectorFilter, requests.get(1).query().bool().filter().get(0));
        String filter = vectorFilter.toString(); assertTrue(filter.contains("allowedUserIds"));
        assertTrue(filter.contains("processingGeneration")); assertTrue(filter.contains("fileMd5"));
        requests.forEach(r -> { assertNull(r.minScore()); assertTrue(r.rescore().isEmpty()); assertEquals(30,r.size()); });
        assertFalse(response.isDegraded()); assertEquals("paper.pdf",response.results().get(0).getFileName());
    }
    @Test void vectorFailureStillRunsBm25AndMarksDegradation() {
        failVector = true; var response = retrieve();
        assertEquals(1, response.results().size()); assertTrue(response.isDegraded());
        assertEquals(Set.of(RetrievalResult.Channel.VECTOR), response.failedChannels()); assertEquals("TEXT_ONLY",response.getRetrievalMode());
        assertEquals(Set.of(RetrievalResult.Channel.BM25),response.results().get(0).getMatchedChannels());
    }
    @Test void bm25FailurePreservesVectorAndMarksDegradation() {
        failBm25 = true; var response = retrieve(); assertEquals("VECTOR_ONLY",response.getRetrievalMode());
        assertEquals(Set.of(RetrievalResult.Channel.BM25),response.failedChannels()); assertEquals(1,response.results().size());
    }
    @Test void embeddingFailureDoesNotPreventBm25() {
        when(embedding.embed(anyList(), anyString(), any())).thenThrow(new IllegalStateException("embedding unavailable"));
        assertEquals("TEXT_ONLY", retrieve().getRetrievalMode()); assertEquals(1,requests.size()); assertTrue(requests.get(0).knn().isEmpty());
    }
    @Test void bothFailedThrowsRatherThanReturnsEmpty() {
        failVector = failBm25 = true;
        assertThrows(RetrievalException.class,this::retrieve); assertEquals(2,requests.size());
        assertThrows(RetrievalException.class,() -> service.searchWithPermission("body","2",5));
    }
    @Test void embeddingAndBm25FailedAlsoThrows() {
        when(embedding.embed(anyList(),anyString(),any())).thenThrow(new IllegalStateException()); failBm25=true;
        assertThrows(RetrievalException.class,this::retrieve);
    }
    @Test void successfulZeroHitsIsNormalEmptyNotFailure() {
        vectorHits=bm25Hits=List.of(); var response=retrieve(); assertTrue(response.isEmpty()); assertFalse(response.isDegraded());
    }
    @Test void emptySurvivingChannelRetainsDegradedMetadata() {
        failVector=true; bm25Hits=List.of(); var response=retrieve(); assertTrue(response.isEmpty()); assertTrue(response.isDegraded());
        assertEquals("TEXT_ONLY",response.getRetrievalMode());
    }
    @ParameterizedTest @ValueSource(ints={1,5,20,21,1000,Integer.MAX_VALUE})
    void topKAndBothCandidateCountsAreBounded(int topK) {
        var response=service.retrieveWithPermission("body","2",topK);
        assertEquals(Math.min(20,topK),response.topK()); assertTrue(response.candidateSize()<=100);
        assertTrue(response.results().size()<=response.topK());
        requests.forEach(r -> assertEquals(response.candidateSize(),r.size()));
        assertEquals(response.candidateSize(),requests.get(0).knn().get(0).k());
        assertTrue(requests.get(0).knn().get(0).numCandidates()<=500);
    }
    @ParameterizedTest @ValueSource(ints={0,-1,Integer.MIN_VALUE})
    void invalidTopKIsRejectedBeforeAnyExternalCalls(int topK) {
        assertThrows(IllegalArgumentException.class,() -> service.retrieveWithPermission("body","2",topK));
        verifyNoInteractions(es,embedding,contents,uploads);
    }
    @Test void emptyQueryRejected() {
        assertThrows(IllegalArgumentException.class,() -> service.retrieve("  ",5));
        assertThrows(IllegalArgumentException.class,() -> service.retrieve(null,5)); verifyNoInteractions(es);
    }
    @Test void oldGenerationAndMissingGenerationAreRejected() {
        vectorHits=bm25Hits=List.of(hit("old",0L),hit("legacy",null),hit("current",1L));
        var response=retrieve(); assertEquals(1,response.results().size()); assertEquals("current",response.results().get(0).getEntryId());
    }
    @Test void parsedContentNeverStartsRecall() {
        ready.setProcessingStatus(FileContent.ProcessingStatus.PARSED); assertTrue(retrieve().isEmpty()); verifyNoInteractions(es,embedding);
    }
    @Test void failedContentNeverStartsRecall() {
        ready.setProcessingStatus(FileContent.ProcessingStatus.FAILED); assertTrue(retrieve().isEmpty()); verifyNoInteractions(es);
    }
    @Test void deletedContentNeverStartsRecall() {
        ready.setDeletedAt(java.time.LocalDateTime.now()); assertTrue(retrieve().isEmpty()); verifyNoInteractions(es);
    }
    @Test void generationChangeWhileEsRunsIsRechecked() {
        afterSearch=() -> ready.setProcessingGeneration(2); assertTrue(retrieve().isEmpty());
        verify(contents,times(2)).findByFileMd5InAndProcessingStatusAndDeletedAtIsNull(anyCollection(),eq(FileContent.ProcessingStatus.INDEXED));
    }
    @Test void indexedToParsedChangeWhileEsRunsIsRechecked() {
        afterSearch=() -> ready.setProcessingStatus(FileContent.ProcessingStatus.PARSED); assertTrue(retrieve().isEmpty());
    }
    @Test void permissionRevocationWhileEsRunsIsRechecked() {
        when(uploads.findAuthorizedContentIds("2",List.of("TEAM"))).thenReturn(List.of("abc"),List.of()); assertTrue(retrieve().isEmpty());
    }
    @Test void unrecognizedFileIsRejectedEvenIfEsReturnsIt() {
        vectorHits.get(0).source().setFileMd5("outside"); assertTrue(retrieve().isEmpty());
    }
    @Test void usernameCompatibilityResolvesCanonicalUserId() {
        assertEquals(1,service.searchWithPermission("body","B",5).size());
        verify(embedding).embed(List.of("body"),"2",EmbeddingClient.UsageType.QUERY);
    }
    @Test void publicEntryRemainsPublicOnlyAndRequiresIndexedGeneration() {
        assertEquals(1,service.retrieve("body",5).results().size());
        String filter=requests.get(0).knn().get(0).filter().get(0).toString();
        assertTrue(filter.contains("public")); assertTrue(filter.contains("processingGeneration")); assertFalse(filter.contains("allowedUserIds"));
    }
    @Test void databaseFailureThrowsWithoutUnscopedRecall() {
        when(contents.findByFileMd5InAndProcessingStatusAndDeletedAtIsNull(anyCollection(),any())).thenThrow(new IllegalStateException("DB down"));
        assertThrows(RetrievalException.class,this::retrieve); verifyNoInteractions(es,embedding);
    }
    @Test void timedOutOrFailedShardsCountAsFailedChannels() throws Exception {
        when(es.search(any(SearchRequest.class),eq(EsDocument.class))).thenReturn(response(vectorHits,true,0),response(bm25Hits,false,1));
        assertThrows(RetrievalException.class,this::retrieve);
    }
    @Test void scopeQueriesAreBulkNotPerHit() {
        vectorHits=bm25Hits=List.of(hit("a",1L),hit("b",1L),hit("c",1L)); retrieve();
        verify(contents,times(2)).findByFileMd5InAndProcessingStatusAndDeletedAtIsNull(anyCollection(),any());
        verify(contents,never()).findByFileMd5(anyString()); verify(uploads).findByFileMd5In(anyList());
    }
    @Test void queryResultsPreserveFigureThroughServiceAndOldEntry() {
        var figure=RrfFusionTest.figure("f",1); figure.source().setProcessingGeneration(1L);
        vectorHits=bm25Hits=List.of(figure); var old=service.searchWithPermission("diagram","2",5).get(0);
        assertEquals(EsDocument.DocumentType.FIGURE,old.getDocumentType()); assertNotNull(old.getImagePath());
        assertEquals(1L,old.getProcessingGeneration()); assertNull(old.getChunkId()); assertEquals("paper.pdf",old.getFileName());
    }
}
