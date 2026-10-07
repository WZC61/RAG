package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.DocumentFigure;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.repository.DocumentFigureRepository;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VectorizationServiceTest {
    private final DocumentVectorRepository rows = mock(DocumentVectorRepository.class);
    private final EmbeddingClient embedding = mock(EmbeddingClient.class);
    private final ElasticsearchService es = mock(ElasticsearchService.class);
    private final DocumentFigureRepository figures = mock(DocumentFigureRepository.class);
    private final FileContentRepository contents = mock(FileContentRepository.class);
    private FileContent content;
    private VectorizationService service;

    @BeforeEach
    void setUp() {
        service = new VectorizationService();
        ReflectionTestUtils.setField(service, "documentVectorRepository", rows);
        ReflectionTestUtils.setField(service, "embeddingClient", embedding);
        ReflectionTestUtils.setField(service, "elasticsearchService", es);
        ReflectionTestUtils.setField(service, "documentFigureRepository", figures);
        ReflectionTestUtils.setField(service, "fileContentRepository", contents);
        var files = mock(com.yizhaoqi.smartpai.repository.FileUploadRepository.class);
        var relation = new com.yizhaoqi.smartpai.model.FileUpload();
        relation.setUserId("user"); relation.setOrgTag("org"); relation.setPublic(true);
        relation.setStatus(com.yizhaoqi.smartpai.model.FileUpload.STATUS_COMPLETED);
        when(files.findAllByFileMd5("abc")).thenReturn(List.of(relation));
        ReflectionTestUtils.setField(service, "fileUploadRepository", files);
        ReflectionTestUtils.setField(service, "contentIndexWriter", new ContentIndexWriter(contents, files, es));
        content = new FileContent();
        content.setFileMd5("abc"); content.setProcessingGeneration(7);
        content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
        when(contents.findForUpdate("abc")).thenReturn(Optional.of(content));
        when(contents.findByFileMd5("abc")).thenReturn(Optional.of(content));
        when(rows.findByFileMd5OrderByChunkIdAsc("abc")).thenReturn(List.of(chunk(1, "first"), chunk(2, "second")));
        when(embedding.embedWithUsage(List.of("first", "second"), "requester", EmbeddingClient.UsageType.UPLOAD))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                        List.of(new float[]{1, 2}, new float[]{3, 4}), 12, "test-model"));
    }

    @Test
    void contentEntryUsesActualGenerationAndPreservesExistingDocumentFields() {
        var result = service.vectorizeWithUsage("abc", 7L, "user", "org", true, "requester");
        List<EsDocument> docs = batches(1).get(0);
        assertEquals(List.of("TEXT:abc:7:1", "TEXT:abc:7:2"), ids(docs));
        EsDocument first = docs.get(0);
        assertEquals("abc", first.getFileMd5());
        assertEquals(1, first.getChunkId());
        assertEquals("first", first.getTextContent());
        assertEquals(4, first.getPageNumber());
        assertEquals("anchor-first", first.getAnchorText());
        assertArrayEquals(new float[]{1, 2}, first.getVector());
        assertEquals("test-model", first.getModelVersion());
        assertEquals("user", first.getUserId());
        assertEquals("org", first.getOrgTag());
        assertTrue(first.isPublic());
        assertEquals(EsDocument.DocumentType.TEXT, first.getDocumentType());
        assertEquals(7L, first.getProcessingGeneration());
        assertEquals(12, result.actualEmbeddingTokens());
        assertEquals(2, result.actualChunkCount());
        verify(rows, never()).saveAll(any());
    }

    @Test
    void bulkFailureThenRetryUsesExactlyTheSameDocumentIds() {
        doThrow(new RuntimeException("partial bulk failure")).doNothing().when(es).bulkIndex(anyList());
        assertThrows(RuntimeException.class,
                () -> service.vectorizeWithUsage("abc", 7L, "user", "org", true, "requester"));
        service.vectorizeWithUsage("abc", 7L, "user", "org", true, "requester");
        var calls = batches(2);
        assertEquals(List.of("TEXT:abc:7:1", "TEXT:abc:7:2"), ids(calls.get(0)));
        assertEquals(ids(calls.get(0)), ids(calls.get(1)));
    }

    @Test
    void differentGenerationsProduceDifferentIdsForTheSameChunks() {
        service.vectorizeWithUsage("abc", 7L, "user", "org", true, "requester");
        content.setProcessingGeneration(8);
        service.vectorizeWithUsage("abc", 8L, "user", "org", true, "requester");
        var calls = batches(2);
        assertEquals(List.of("TEXT:abc:8:1", "TEXT:abc:8:2"), ids(calls.get(1)));
        assertNotEquals(ids(calls.get(0)), ids(calls.get(1)));
    }

    @Test
    void legacyUsageEntryRetainsItsSignatureAndStableUserScopedNamespace() {
        service.vectorizeWithUsage("abc", "user", "org", true, "requester");
        service.vectorizeWithUsage("abc", "user", "org", true, "requester");
        var calls = batches(2);
        assertEquals(List.of("LEGACY_TEXT:abc:user:1", "LEGACY_TEXT:abc:user:2"), ids(calls.get(0)));
        assertEquals(ids(calls.get(0)), ids(calls.get(1)));
    }

    @Test
    void legacyVectorizeOverloadsStillWork() {
        when(embedding.embedWithUsage(List.of("first", "second"), "user", EmbeddingClient.UsageType.UPLOAD))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                        List.of(new float[]{1, 2}, new float[]{3, 4}), 12, "test-model"));
        service.vectorize("abc", "user", "org", true);
        service.vectorize("abc", "user", "org", true, "requester");
        var calls = batches(2);
        assertEquals(ids(calls.get(0)), ids(calls.get(1)));
    }

    @Test
    void invalidContentGenerationCannotFallBackToLegacyIds() {
        assertThrows(IllegalArgumentException.class,
                () -> service.vectorizeWithUsage("abc", 0L, "user", "org", true, "requester"));
        verifyNoInteractions(rows, embedding, es);
    }

    @Test
    void textAndFigureUseSeparateEmbeddingBatchesAndOneBulkWithCompleteMetadata() {
        DocumentFigure figure = figure(" description ");
        figure.setCaption(" caption "); figure.setOcrText(" OCR ");
        var result = indexWithFigure(figure, "caption\n\ndescription\n\nOCR");
        var docs = batches(1).get(0);
        assertEquals(3, docs.size());
        var doc = docs.get(2);
        assertEquals("FIGURE:abc:7:4:2", doc.getId());
        assertEquals(EsDocument.DocumentType.FIGURE, doc.getDocumentType());
        assertEquals(7L, doc.getProcessingGeneration());
        assertEquals("abc", doc.getFileMd5());
        assertNull(doc.getChunkId());
        assertEquals(4, doc.getPageNumber()); assertEquals(2, doc.getFigureIndex());
        assertEquals("Figure 2", doc.getFigureLabel());
        assertEquals("figures/abc/7/page-4-figure-2.png", doc.getImagePath());
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0), doc.getBbox());
        assertEquals("context is not embedding input", doc.getNearbyText());
        assertEquals(" caption ", doc.getCaption()); assertEquals(" description ", doc.getDescription());
        assertEquals(" OCR ", doc.getOcrText());
        assertEquals("caption\n\ndescription\n\nOCR", doc.getTextContent());
        assertArrayEquals(new float[]{5, 6}, doc.getVector());
        assertTrue(doc.isPublic()); assertEquals("user", doc.getUserId()); assertEquals("org", doc.getOrgTag());
        assertEquals(15, result.actualEmbeddingTokens()); assertEquals(3, result.actualChunkCount());
        var order = inOrder(embedding, es);
        order.verify(embedding).embedWithUsage(List.of("first", "second"), "requester", EmbeddingClient.UsageType.UPLOAD);
        order.verify(embedding).embedWithUsage(List.of("caption\n\ndescription\n\nOCR"), "requester", EmbeddingClient.UsageType.UPLOAD);
        order.verify(es).bulkIndex(anyList());
    }

    @Test
    void figureOnlyIsIndexedAndEmptyContextFieldsAreOmitted() {
        when(rows.findByFileMd5OrderByChunkIdAsc("abc")).thenReturn(List.of());
        DocumentFigure figure = figure("description"); figure.setCaption("  "); figure.setOcrText(null);
        var result = indexWithFigure(figure, "description");
        assertEquals(List.of("FIGURE:abc:7:4:2"), ids(batches(1).get(0)));
        assertEquals(1, result.actualChunkCount());
        assertEquals(3, result.actualEmbeddingTokens());
        verify(embedding, times(1)).embedWithUsage(anyList(), anyString(), any());
    }

    @Test
    void emptyAndWhitespaceTextWithoutFiguresFailsBeforeEmbeddingOrEs() {
        when(rows.findByFileMd5OrderByChunkIdAsc("abc")).thenReturn(List.of(chunk(1, "  "), chunk(2, null)));
        assertThrows(IllegalStateException.class, () -> service.vectorizeWithUsage("abc", 7, "user", "org", true, "requester"));
        verifyNoInteractions(embedding, es);
    }

    @Test
    void missingFigureDescriptionFailsEvenIfCaptionIsPresent() {
        var figure = figure("  "); figure.setCaption("caption");
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc("abc", 7L)).thenReturn(List.of(figure));
        assertThrows(IllegalStateException.class, () -> service.vectorizeWithUsage("abc", 7, "user", "org", true, "requester"));
        verifyNoInteractions(embedding, es);
    }

    @Test
    void textEmbeddingFailurePreventsFigureEmbeddingAndEsWrites() {
        when(embedding.embedWithUsage(anyList(), anyString(), any())).thenThrow(new IllegalStateException("embedding failed"));
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc("abc", 7L))
                .thenReturn(List.of(figure("description")));
        assertThrows(IllegalStateException.class, () -> service.vectorizeWithUsage("abc", 7, "user", "org", true, "requester"));
        verifyNoInteractions(es);
        verify(embedding, times(1)).embedWithUsage(anyList(), anyString(), any());
    }

    @Test
    void figureEmbeddingFailureDoesNotWriteTextOnlySuccessToEs() {
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc("abc", 7L))
                .thenReturn(List.of(figure("description")));
        when(embedding.embedWithUsage(List.of("description"), "requester", EmbeddingClient.UsageType.UPLOAD))
                .thenThrow(new IllegalStateException("figure embedding failed"));
        assertThrows(IllegalStateException.class, () -> service.vectorizeWithUsage("abc", 7, "user", "org", true, "requester"));
        verifyNoInteractions(es);
    }

    @Test
    void multimodalBulkRetryOverwritesSameTextAndFigureIds() {
        doThrow(new RuntimeException("partial bulk failure")).doNothing().when(es).bulkIndex(anyList());
        assertThrows(RuntimeException.class, () -> indexWithFigure(figure("description"), "description"));
        indexWithFigure(figure("description"), "description");
        var calls = batches(2);
        assertEquals(List.of("TEXT:abc:7:1", "TEXT:abc:7:2", "FIGURE:abc:7:4:2"), ids(calls.get(0)));
        assertEquals(ids(calls.get(0)), ids(calls.get(1)));
    }

    @Test
    void generationChangeDuringEmbeddingPreventsEsWrites() {
        when(embedding.embedWithUsage(anyList(), anyString(), any())).thenAnswer(call -> {
            content.setProcessingGeneration(8);
            return new EmbeddingClient.EmbeddingUsageResult(List.of(new float[]{1, 2}, new float[]{3, 4}), 12, "model");
        });
        assertThrows(IllegalStateException.class, () -> service.vectorizeWithUsage("abc", 7, "user", "org", true, "requester"));
        verifyNoInteractions(es);
    }

    @Test
    void legacyEntryDoesNotQueryFiguresOrContentState() {
        service.vectorizeWithUsage("abc", "user", "org", true, "requester");
        verifyNoInteractions(figures, contents);
        assertNull(batches(1).get(0).get(0).getProcessingGeneration());
    }

    @Test
    void changingEmbeddingModelAcrossModalitiesFailsBeforeEsWrite() {
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc("abc", 7L))
                .thenReturn(List.of(figure("description")));
        when(embedding.embedWithUsage(List.of("description"), "requester", EmbeddingClient.UsageType.UPLOAD))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(List.of(new float[]{5, 6}), 3, "different-model"));
        assertThrows(IllegalStateException.class, () -> service.vectorizeWithUsage("abc", 7, "user", "org", true, "requester"));
        verifyNoInteractions(es);
    }

    @Test
    void unknownPpBboxIsPreservedAsAnEmptyArrayAndDoesNotBlockIndexing() {
        var figure = figure("description"); figure.setBbox("[]");
        indexWithFigure(figure, "description");
        assertEquals(List.of(), batches(1).get(0).get(2).getBbox());
    }

    @Test
    void corruptedFigureBboxIsNotSilentlySavedToEs() {
        var figure = figure("description"); figure.setBbox("[1,\"invalid\",3,4]");
        assertThrows(IllegalStateException.class, () -> indexWithFigure(figure, "description"));
        verifyNoInteractions(es);
    }

    private VectorizationService.VectorizationUsageResult indexWithFigure(DocumentFigure figure, String expectedText) {
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc("abc", 7L)).thenReturn(List.of(figure));
        when(embedding.embedWithUsage(List.of(expectedText), "requester", EmbeddingClient.UsageType.UPLOAD))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(List.of(new float[]{5, 6}), 3, "test-model"));
        return service.vectorizeWithUsage("abc", 7, "user", "org", true, "requester");
    }

    private DocumentFigure figure(String description) {
        var figure = new DocumentFigure();
        figure.setFileMd5("abc"); figure.setProcessingGeneration(7L);
        figure.setPageNumber(4); figure.setFigureIndex(2); figure.setFigureLabel("Figure 2");
        figure.setImagePath("figures/abc/7/page-4-figure-2.png"); figure.setBbox("[1,2,3,4]");
        figure.setDescription(description); figure.setNearbyText("context is not embedding input");
        return figure;
    }

    private DocumentVector chunk(int index, String text) {
        DocumentVector row = new DocumentVector();
        row.setChunkId(index);
        row.setTextContent(text);
        row.setPageNumber(4);
        row.setAnchorText("anchor-" + text);
        return row;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<List<EsDocument>> batches(int count) {
        ArgumentCaptor<List<EsDocument>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(es, times(count)).bulkIndex(captor.capture());
        return captor.getAllValues();
    }

    private List<String> ids(List<EsDocument> docs) {
        return docs.stream().map(EsDocument::getId).toList();
    }
}
