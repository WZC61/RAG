package com.yizhaoqi.smartpai.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.*;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EsIndexInitializerTest {
    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
    private EsIndexInitializer initializer;

    @BeforeEach
    void setUp() throws Exception {
        initializer = new EsIndexInitializer();
        ReflectionTestUtils.setField(initializer, "esClient", client);
        ReflectionTestUtils.setField(initializer, "mappingResource", new ClassPathResource("es-mappings/knowledge_base.json"));
        when(client.indices()).thenReturn(indices);
        when(indices.getMapping(any(GetMappingRequest.class))).thenReturn(GetMappingResponse.of(response -> response
                .result("knowledge_base", record -> record.mappings(mapping -> mapping
                        .properties("caption", property -> property.text(text -> text.analyzer("ik_max_word")))))));
    }

    @Test
    void newIndexReceivesCompleteMapping() throws Exception {
        when(indices.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(false));
        initializer.run();
        var request = ArgumentCaptor.forClass(CreateIndexRequest.class);
        verify(indices).create(request.capture());
        assertEquals("knowledge_base", request.getValue().index());
        assertTrue(request.getValue().mappings().properties().containsKey("documentType"));
        verify(indices, never()).putMapping(any(PutMappingRequest.class));
    }

    @Test
    void existingIndexReceivesPutMappingInsteadOfBeingIgnored() throws Exception {
        when(indices.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(true));
        when(indices.putMapping(any(PutMappingRequest.class)))
                .thenReturn(PutMappingResponse.of(response -> response.acknowledged(true)));
        initializer.run();
        var request = ArgumentCaptor.forClass(PutMappingRequest.class);
        verify(indices).putMapping(request.capture());
        assertEquals(java.util.List.of("knowledge_base"), request.getValue().index());
        assertTrue(request.getValue().properties().containsKey("processingGeneration"));
        assertTrue(request.getValue().properties().containsKey("imagePath"));
        assertTrue(request.getValue().properties().containsKey("public"));
        assertFalse(request.getValue().properties().containsKey("caption"));
        verify(indices, never()).create(any(CreateIndexRequest.class));
    }

    @Test
    void unacknowledgedMappingOrIncompatibleExistingTypeFailsClearly() throws Exception {
        when(indices.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(true));
        when(indices.putMapping(any(PutMappingRequest.class)))
                .thenReturn(PutMappingResponse.of(response -> response.acknowledged(false)));
        assertThrows(RuntimeException.class, () -> initializer.run());
        when(indices.putMapping(any(PutMappingRequest.class))).thenThrow(new IllegalStateException("mapper conflict"));
        assertThrows(RuntimeException.class, () -> initializer.run());
    }

    @Test
    void incompatibleExistingFieldIsRejectedBeforePutMapping() throws Exception {
        when(indices.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(true));
        when(indices.getMapping(any(GetMappingRequest.class))).thenReturn(GetMappingResponse.of(response -> response
                .result("knowledge_base", record -> record.mappings(mapping -> mapping
                        .properties("processingGeneration", property -> property.keyword(keyword -> keyword))))));
        assertThrows(RuntimeException.class, () -> initializer.run());
        verify(indices, never()).putMapping(any(PutMappingRequest.class));
    }

    @Test
    void wrongExistingVectorDimensionIsRejectedWithoutDeletingIndex() throws Exception {
        when(indices.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(true));
        when(indices.getMapping(any(GetMappingRequest.class))).thenReturn(GetMappingResponse.of(response -> response
                .result("knowledge_base", record -> record.mappings(mapping -> mapping
                        .properties("vector", property -> property.denseVector(vector -> vector.dims(768)))))));
        assertThrows(RuntimeException.class, () -> initializer.run());
        verify(indices, never()).putMapping(any(PutMappingRequest.class));
    }
}
