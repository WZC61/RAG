package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.*;
import com.yizhaoqi.smartpai.exception.RetrievalException;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yizhaoqi.smartpai.service.RagContextAssemblerTest.*;

/** Service integration: real context/tools/prompt/history serialization; external I/O is isolated. */
class ChatRagIntegrationTest {
    HybridSearchService search=mock(HybridSearchService.class);
    LlmProviderRouter router=mock(LlmProviderRouter.class);
    DeepSeekClient summary=mock(DeepSeekClient.class);
    ChatGenerationStateService state=mock(ChatGenerationStateService.class);
    ChatSessionRegistry sockets=mock(ChatSessionRegistry.class);
    RedisTemplate<String,String> redis=mock(RedisTemplate.class);
    ValueOperations<String,String> values=mock(ValueOperations.class);
    ConversationRepository conversations=mock(ConversationRepository.class);
    ObjectMapper json=new ObjectMapper();
    Map<String,String> cache=new HashMap<>();
    List<Conversation> saved=new ArrayList<>();
    List<Map<String,Object>> sent=new ArrayList<>();
    List<List<Map<String,Object>>> prompts=new ArrayList<>();
    Queue<LlmProviderRouter.ReActTurn> turns=new ArrayDeque<>();
    ChatHandler chat;
    ConversationService history=new ConversationService();
    int generation;
    @BeforeEach void setup() {
        var users=mock(UserRepository.class); var sessions=mock(ConversationSessionRepository.class);
        User user=new User(); user.setId(1L); user.setUsername("user");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        ReflectionTestUtils.setField(history,"userRepository",users);
        ReflectionTestUtils.setField(history,"conversationRepository",conversations);
        ReflectionTestUtils.setField(history,"sessionRepository",sessions);
        ReflectionTestUtils.setField(history,"objectMapper",json);
        ReflectionTestUtils.setField(history,"redisTemplate",redis);
        when(conversations.save(any())).thenAnswer(a->{Conversation c=a.getArgument(0);c.setId((long)saved.size()+1);saved.add(c);return c;});
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(mock(HashOperations.class));
        when(values.get(anyString())).thenAnswer(a->cache.get(a.getArgument(0)));
        doAnswer(a->{cache.put(a.getArgument(0),a.getArgument(1));return null;})
                .when(values).set(anyString(),anyString(),any(java.time.Duration.class));
        cache.put("user:1:current_conversation","conversation");
        when(state.createGeneration(anyString(),anyString(),anyString())).thenAnswer(a->snapshot("g"+(++generation),Map.of()));
        var executor=mock(ThreadPoolTaskExecutor.class);
        doAnswer(a->{((Runnable)a.getArgument(0)).run();return null;}).when(executor).execute(any(Runnable.class));
        var assembler=new RagContextAssembler();
        var tools=new AgentToolRegistry(search,summary,mock(StringRedisTemplate.class),mock(ElasticsearchClient.class),
                mock(FileUploadRepository.class),assembler);
        var realPrompt=new LlmProviderRouter(new AiProperties(),mock(RateLimitService.class),mock(UsageQuotaService.class),
                mock(ModelProviderConfigService.class),json);
        when(router.buildReActMessages(anyString(),anyString(),anyList(),anyString())).thenAnswer(a->
                realPrompt.buildReActMessages(a.getArgument(0),a.getArgument(1),a.getArgument(2),a.getArgument(3)));
        when(router.streamReActTurn(anyString(),anyList(),anyList(),anyInt(),any(),any(),any())).thenAnswer(a->{
            List<Map<String,Object>> messages=a.getArgument(1);
            prompts.add(messages.stream().<Map<String,Object>>map(m->new LinkedHashMap<>(m)).toList());
            var turn=turns.remove();
            if (!turn.content().isEmpty()) ((Consumer<String>)a.getArgument(4)).accept(turn.content());
            ((Consumer<LlmProviderRouter.ReActTurn>)a.getArgument(6)).accept(turn);
            var constructor=LlmProviderRouter.StreamHandle.class.getDeclaredConstructor(reactor.core.Disposable.class,Runnable.class);
            constructor.setAccessible(true);
            return constructor.newInstance(reactor.core.Disposables.disposed(),(Runnable)()->{});
        });
        doAnswer(a->{sent.add(new LinkedHashMap<>(a.getArgument(1)));return null;}).when(sockets).sendJsonToUser(eq("1"),anyMap());
        chat=new ChatHandler(redis,search,router,mock(RateLimitService.class),history,state,sockets,tools,json,executor,assembler);
    }
    ChatGenerationStateService.GenerationSnapshot snapshot(String id,Map<String,Map<String,Object>> refs) {
        return new ChatGenerationStateService.GenerationSnapshot(id,"1","conversation","q",
                ChatGenerationStateService.GenerationStatus.STREAMING,"","now","now",null,refs);
    }
    LlmProviderRouter.ReActTurn answer(String answer) {
        return new LlmProviderRouter.ReActTurn(answer,List.of(),Map.of("role","assistant","content",answer),"stop",10,5);
    }
    LlmProviderRouter.ReActTurn tool(String name,String query) {
        var call=new LlmProviderRouter.ToolCallDecision("call",name,Map.of(name.equals("generate_summary")?"topic":"query",query));
        return new LlmProviderRouter.ReActTurn("",List.of(call),Map.of("role","assistant","content",""),"tool_calls",10,0);
    }
    void ask(String question) {
        var socket=mock(WebSocketSession.class);when(socket.getId()).thenReturn("socket");chat.processMessage("1",question,socket);
    }
    Map<String,Object> completion() {return sent.stream().filter(m->"completion".equals(m.get("type"))).reduce((a,b)->b).orElseThrow();}
    Map<String,Map<String,Object>> refs() {return (Map<String,Map<String,Object>>)completion().get("referenceMappings");}

    @Test void proactiveMixedRetrievalReachesPromptAndHistoryWithIdenticalReferences() throws Exception {
        when(search.retrieveWithPermission("question","1",5)).thenReturn(response(text("T"),figure("F")));
        turns.add(answer("text [1], figure [2]"));ask("question");
        String system=(String)prompts.get(0).get(0).get("content");
        assertTrue(system.contains("[1] TEXT"));assertTrue(system.contains("[2] FIGURE"));assertFalse(system.contains("private/figures"));
        verify(search).retrieveWithPermission("question","1",5);
        assertEquals("FIGURE",refs().get("2").get("documentType"));assertEquals("F",refs().get("2").get("entryId"));
        assertFalse(refs().get("2").containsKey("imagePath"));
        assertEquals(json.readTree(json.writeValueAsString(refs())),json.readTree(saved.get(0).getReferenceMappingsJson()));
        assertEquals(json.readTree(json.writeValueAsString(refs())),json.readTree(json.writeValueAsString(history.toMessageHistory(saved,false).get(1).get("referenceMappings"))));
        verify(state).markCompleted(eq("g1"),eq(refs()));assertEquals("OK",completion().get("retrievalStatus"));
    }
    @Test void textOnlyWorksWithoutToolCalls() {
        when(search.retrieveWithPermission("q","1",5)).thenReturn(response(text("T")));turns.add(answer("answer [1]"));ask("q");
        assertEquals("TEXT",refs().get("1").get("documentType"));assertEquals(1,saved.size());
    }
    @Test void figureOnlyWorksWithoutText() {
        when(search.retrieveWithPermission("q","1",5)).thenReturn(response(figure("F")));turns.add(answer("answer [1]"));ask("q");
        assertEquals("FIGURE",refs().get("1").get("documentType"));assertEquals(2,refs().get("1").get("figureIndex"));
    }
    @Test void emptyStillCallsModelWithExplicitInsufficientEvidenceAndNoInventedSource() {
        when(search.retrieveWithPermission("q","1",5)).thenReturn(response());turns.add(answer("现有资料不足以确定"));ask("q");
        assertEquals("EMPTY",completion().get("retrievalStatus"));assertNull(completion().get("referenceMappings"));
        assertTrue(prompts.get(0).get(0).get("content").toString().contains("正常检索"));assertNull(saved.get(0).getReferenceMappingsJson());
    }
    @Test void degradedResultsAreUsableAndVisible() {
        when(search.retrieveWithPermission("q","1",5)).thenReturn(new RetrievalResponse(List.of(text("T")),
                Set.of(RetrievalResult.Channel.BM25),5,30));turns.add(answer("answer [1]"));ask("q");
        assertEquals("DEGRADED",completion().get("retrievalStatus"));assertEquals(true,refs().get("1").get("degraded"));
        assertTrue(prompts.get(0).get(0).get("content").toString().contains("检索降级"));
    }
    @Test void totalRetrievalFailureDoesNotCallModelOrPersistFalseEmptyAnswer() {
        when(search.retrieveWithPermission("q","1",5)).thenThrow(new RetrievalException("retrieval unavailable"));ask("q");
        assertTrue(prompts.isEmpty());assertTrue(saved.isEmpty());assertEquals("failed",completion().get("status"));
        assertEquals("FAILED",completion().get("retrievalStatus"));
        assertTrue(sent.stream().anyMatch(m->"RETRIEVAL_FAILED".equals(m.get("code"))));
        verify(state).markFailed(eq("g1"),anyString());
    }
    @Test void additionalSearchAppendsSourceAndRefreshesOneSystemContext() {
        when(search.retrieveWithPermission("q","1",5)).thenReturn(response(text("A")));
        when(search.retrieveWithPermission("more","1",5)).thenReturn(response(text("B"),text("A")));
        turns.add(tool("search_knowledge","more"));turns.add(answer("answer [1] [2]"));ask("q");
        assertEquals("A",refs().get("1").get("entryId"));assertEquals("B",refs().get("2").get("entryId"));
        var second=prompts.get(1);assertTrue(second.get(0).get("content").toString().contains("[2] TEXT"));
        assertFalse(second.get(second.size()-1).get("content").toString().contains("Reliable text evidence"));
    }
    @Test void summaryStreamUsesSameRegistryAndHistoryReferences() throws Exception {
        when(search.retrieveWithPermission("q","1",5)).thenReturn(response(text("A")));
        when(search.retrieveWithPermission("topic","1",5)).thenReturn(response(figure("F")));
        when(summary.summarizeContext(eq("1"),eq("topic"),any(),any())).thenAnswer(a->{
            RagContextAssembler.Context c=a.getArgument(2);assertTrue(c.text().contains("[2] FIGURE"));
            verify(state,atLeastOnce()).updateReferenceMappings(eq("g1"),argThat(m->m.containsKey("2")));
            ((Consumer<String>)a.getArgument(3)).accept("summary [2]");return "summary [2]";
        });
        turns.add(tool("generate_summary","topic"));ask("q");
        assertEquals("F",refs().get("2").get("entryId"));assertTrue(saved.get(0).getAnswer().contains("summary [2]"));
        assertEquals(json.readTree(json.writeValueAsString(refs())),json.readTree(json.writeValueAsString(history.toMessageHistory(saved,false).get(1).get("referenceMappings"))));assertEquals(1,prompts.size());
    }
    @Test void partialSummaryFailureKeepsAlreadyPublishedReferences() {
        when(search.retrieveWithPermission("q","1",5)).thenReturn(response(text("A")));
        when(search.retrieveWithPermission("topic","1",5)).thenReturn(response(figure("F")));
        when(summary.summarizeContext(anyString(),anyString(),any(),any())).thenAnswer(a->{
            ((Consumer<String>)a.getArgument(3)).accept("partial [2]");throw new RuntimeException("model stopped");});
        turns.add(tool("generate_summary","topic"));ask("q");
        assertEquals("F",refs().get("2").get("entryId"));assertTrue(saved.get(0).getAnswer().contains("partial [2]"));
    }
    @Test void multipleConversationTurnsHaveSeparateRegistriesAndPreserveEarlierHistory() throws Exception {
        when(search.retrieveWithPermission("q1","1",5)).thenReturn(response(text("A"),figure("B")));
        when(search.retrieveWithPermission("q2","1",5)).thenReturn(response(text("C")));
        turns.add(answer("first [1] [2]"));ask("q1");turns.add(answer("next [1]"));ask("q2");
        assertEquals(2,saved.size());assertEquals("C",refs().get("1").get("entryId"));assertEquals(1,refs().size());
        assertTrue(prompts.get(1).stream().anyMatch(m->"first".equals(String.valueOf(m.get("content")).strip())));
        assertEquals("first [1] [2]", saved.get(0).getAnswer());
        assertEquals("B",((Map<?,?>)json.readValue(saved.get(0).getReferenceMappingsJson(),Map.class).get("2")).get("entryId"));
    }
    @Test void persistedOldReferencePayloadRemainsReadable() {
        var old=Map.<String,Map<String,Object>>of("1",Map.of("fileMd5","old","pageNumber",1,"chunkId",3));
        when(state.getGeneration("old-g")).thenReturn(Optional.of(snapshot("old-g",old)));
        var result=chat.getReferenceDetail("old-g",1);assertEquals("old",result.fileMd5());assertNull(result.documentType());
    }
    @Test void referenceLookupChecksAnswerOwner() {
        when(state.getGenerationForUser("g1","outsider")).thenReturn(Optional.empty());
        assertNull(chat.getReferenceDetailForUser("g1",1,"outsider"));verify(state,never()).getGeneration("g1");
    }
    @Test void stopDuringSummaryRetrievalDoesNotStartSummaryModel() {
        when(search.retrieveWithPermission("q","1",5)).thenReturn(response(text("A")));
        when(search.retrieveWithPermission("topic","1",5)).thenAnswer(a->{
            chat.stopResponse("1","g1"); return response(figure("F"));
        });
        turns.add(tool("generate_summary","topic")); ask("q");
        verifyNoInteractions(summary); assertTrue(saved.isEmpty());
        verify(state,never()).markFailed(anyString(),anyString());
    }
    @Test void stopDuringRetrievalDoesNotCallModelOrTurnCancellationIntoFailure() {
        when(state.getGenerationForUser("g1","1")).thenReturn(Optional.of(snapshot("g1",Map.of())));
        when(search.retrieveWithPermission("q","1",5)).thenAnswer(a->{chat.stopResponse("1","g1");throw new RetrievalException("stopped");});
        ask("q");assertTrue(prompts.isEmpty());assertTrue(saved.isEmpty());verify(state,never()).markFailed(anyString(),anyString());
    }
}
