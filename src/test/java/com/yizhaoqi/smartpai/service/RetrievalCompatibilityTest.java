package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.controller.SearchController;
import com.yizhaoqi.smartpai.entity.*;
import com.yizhaoqi.smartpai.exception.RetrievalException;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetrievalCompatibilityTest {
    HybridSearchService search;
    DeepSeekClient llm;
    AgentToolRegistry tools;
    SearchController controller;
    @BeforeEach void setup() {
        search=mock(HybridSearchService.class); llm=mock(DeepSeekClient.class);
        tools=new AgentToolRegistry(search,llm,mock(StringRedisTemplate.class),mock(ElasticsearchClient.class),mock(FileUploadRepository.class),new RagContextAssembler());
        controller=new SearchController(); ReflectionTestUtils.setField(controller,"hybridSearchService",search);
    }
    RetrievalResponse degradedEmpty() {
        return new RetrievalResponse(List.of(),Set.of(RetrievalResult.Channel.VECTOR),5,30);
    }
    @Test void searchToolRetainsEmptyDegradationWithoutChangingItsResultListContract() {
        when(search.retrieveWithPermission("query","2",5)).thenReturn(degradedEmpty());
        var result=tools.executeTool("search_knowledge",Map.of("query","query"),"2");
        assertTrue(result.success()); assertEquals(List.of(),result.data().get("results"));
        assertEquals(true,result.data().get("degraded")); assertEquals("TEXT_ONLY",result.data().get("retrievalMode"));
    }
    @Test void summaryKeepsSearchResultInputAndSourcesIncludingFigures() {
        var figure=new RrfFusion().fuse(List.of(RrfFusionTest.figure("f",1)),List.of(),5);
        var response=new RetrievalResponse(figure,Set.of(),5,30);
        when(search.retrieveWithPermission("topic","2",5)).thenReturn(response);
        when(llm.summarizeContext(eq("2"),eq("topic"),any(),isNull())).thenReturn("summary");
        var result=tools.executeTool("generate_summary",Map.of("topic","topic"),"2");
        var source=(SearchResult)((List<?>)result.data().get("sources")).get(0);
        assertEquals(EsDocument.DocumentType.FIGURE,source.getDocumentType()); assertNotNull(source.getImagePath());
        assertEquals(false,result.data().get("degraded"));
    }
    @Test void retrievalFailureNeverCallsSummaryModel() {
        when(search.retrieveWithPermission(anyString(),anyString(),anyInt())).thenThrow(new RetrievalException("both failed"));
        assertThrows(RetrievalException.class,() -> tools.executeTool("generate_summary",Map.of("topic","topic"),"2"));
        assertThrows(RetrievalException.class,() -> tools.executeTool("search_knowledge",Map.of("query","query"),"2"));
        verifyNoInteractions(llm);
    }
    @Test void apiMakesDegradedEmptyVisibleAndKeepsDataAsLegacyList() {
        when(search.retrieveWithPermission("query","2",5)).thenReturn(degradedEmpty());
        var response=controller.hybridSearch("query",5,"2");
        assertEquals(200,response.get("code")); assertEquals(List.of(),response.get("data"));
        assertEquals(true,response.get("degraded")); assertEquals(Set.of(RetrievalResult.Channel.VECTOR),response.get("failedChannels"));
    }
    @Test void apiDualFailureIsErrorRatherThanSuccessfulEmpty() {
        when(search.retrieveWithPermission("query","2",5)).thenThrow(new RetrievalException("both failed"));
        assertEquals(500,controller.hybridSearch("query",5,"2").get("code"));
    }
    @Test void apiPublicCompatibilityUsesPublicRetrieval() {
        when(search.retrieve("query",5)).thenReturn(new RetrievalResponse(List.of(),Set.of(),5,30));
        assertEquals(200,controller.hybridSearch("query",5,null).get("code"));
        verify(search).retrieve("query",5); verify(search,never()).retrieveWithPermission(anyString(),anyString(),anyInt());
    }
    @Test void apiInvalidArgumentIsExplicit() {
        when(search.retrieve("query",0)).thenThrow(new IllegalArgumentException("topK must be positive"));
        assertEquals(400,controller.hybridSearch("query",0,null).get("code"));
    }
}
