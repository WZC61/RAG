package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.yizhaoqi.smartpai.model.ContentAcl;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElasticsearchAclTest {
    ElasticsearchClient client; ElasticsearchService service;
    @BeforeEach void setup() {
        client=mock(ElasticsearchClient.class); service=new ElasticsearchService(); ReflectionTestUtils.setField(service,"esClient",client);
    }
    @Test void replacementTargetsAllTypesAndOverwritesCompleteAcl() throws Exception {
        when(client.updateByQuery(any(UpdateByQueryRequest.class))).thenReturn(UpdateByQueryResponse.of(b -> b.timedOut(false).versionConflicts(0L)));
        service.replaceAcl("abc",new ContentAcl(List.of("A","B"),List.of("TEAM"),false));
        var request=ArgumentCaptor.forClass(UpdateByQueryRequest.class); verify(client).updateByQuery(request.capture());
        assertEquals("fileMd5",request.getValue().query().term().field()); assertEquals("abc",request.getValue().query().term().value().stringValue());
        assertTrue(request.getValue().refresh());
        var script=request.getValue().script().inline(); assertTrue(script.source().contains("ctx._source.allowedUserIds=params.users"));
        assertTrue(script.source().contains("ctx._source.allowedOrgTags=params.orgs")); assertTrue(script.source().contains("ctx._source.public=params.published"));
        assertEquals(List.of("A","B"),script.params().get("users").to(List.class)); assertFalse(script.params().get("published").to(Boolean.class));
    }
    @Test void updateVersionConflictIsRetriedInsteadOfSilentlyAccepted() throws Exception {
        when(client.updateByQuery(any(UpdateByQueryRequest.class))).thenReturn(UpdateByQueryResponse.of(b -> b.timedOut(false).versionConflicts(1L)));
        assertThrows(IllegalStateException.class,()->service.replaceAcl("abc",new ContentAcl(List.of(),List.of(),false)));
    }
    @Test void deleteTimeoutIsNotTreatedAsComplete() throws Exception {
        when(client.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(DeleteByQueryResponse.of(b -> b.timedOut(true).versionConflicts(0L)));
        assertThrows(RuntimeException.class,()->service.deleteByFileMd5("abc"));
    }
    @Test void deleteRefreshesAndChecksConflicts() throws Exception {
        when(client.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(DeleteByQueryResponse.of(b -> b.timedOut(false).versionConflicts(0L)));
        service.deleteByFileMd5("abc"); var request=ArgumentCaptor.forClass(DeleteByQueryRequest.class);verify(client).deleteByQuery(request.capture());
        assertTrue(request.getValue().refresh()); assertEquals("abc",request.getValue().query().term().value().stringValue());
    }
}
