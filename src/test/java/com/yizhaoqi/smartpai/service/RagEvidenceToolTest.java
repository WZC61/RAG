package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.exception.RetrievalException;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yizhaoqi.smartpai.service.RagContextAssemblerTest.*;

class RagEvidenceToolTest {
    HybridSearchService search=mock(HybridSearchService.class);
    DeepSeekClient llm=mock(DeepSeekClient.class);
    RagContextAssembler assembler=new RagContextAssembler();
    AgentToolRegistry tools=new AgentToolRegistry(search,llm,mock(StringRedisTemplate.class),
            mock(ElasticsearchClient.class),mock(FileUploadRepository.class),assembler);
    @Test void sharedSearchReturnsNumbersAndDoesNotDuplicateEvidenceBodies() {
        var session=assembler.newSession(); session.add(response(text("A")),"q");
        when(search.retrieveWithPermission("next","1",5)).thenReturn(response(text("B"),text("A")));
        var result=tools.executeToolWithEvidence("search_knowledge",Map.of("query","next"),"1",null,session,c->{});
        assertEquals(List.of(2,1),result.data().get("evidenceNumbers"));
        assertFalse(result.content().contains("Reliable text evidence"));
        assertEquals(2,session.snapshot().evidence().size());
    }
    @Test void summaryReceivesExactlyItsAssignedEvidenceAndPublishesBeforeStreaming() {
        var session=assembler.newSession(); session.add(response(text("A")),"q");
        when(search.retrieveWithPermission("topic","1",5)).thenReturn(response(figure("B")));
        List<String> order=new ArrayList<>();
        when(llm.summarizeContext(eq("1"),eq("topic"),any(),any())).thenAnswer(call->{
            RagContextAssembler.Context c=call.getArgument(2);
            assertEquals(List.of(2),c.evidence().stream().map(RagContextAssembler.Evidence::number).toList());
            assertTrue(c.text().contains("[2] FIGURE")); assertFalse(c.text().contains("[1]"));
            Consumer<String> stream=call.getArgument(3); stream.accept("summary [2]"); return "summary [2]";
        });
        var result=tools.executeToolWithEvidence("generate_summary",Map.of("topic","topic"),"1",
                chunk->order.add("chunk"),session,c->{assertEquals(2,c.evidence().size());order.add("references");});
        assertEquals(List.of("references","chunk"),order); assertEquals(List.of(2),result.data().get("evidenceNumbers"));
        assertTrue(result.streamedToUser());
    }
    @Test void failureDoesNotPretendEmptyOrCallSummary() {
        when(search.retrieveWithPermission(anyString(),anyString(),anyInt())).thenThrow(new RetrievalException("failed"));
        assertThrows(RetrievalException.class,()->tools.executeTool("generate_summary",Map.of("topic","t"),"1"));
        verifyNoInteractions(llm);
    }
    @Test void oldToolEntryUsesSameBoundedFigureContext() {
        when(search.retrieveWithPermission("q","1",5)).thenReturn(response(figure("F")));
        var r=tools.executeTool("search_knowledge",Map.of("query","q"),"1");
        assertTrue(r.content().contains("[1] FIGURE")); assertTrue(r.content().contains("Description:"));
        assertFalse(r.content().contains("private/figures"));
    }
}
