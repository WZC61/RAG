package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import com.yizhaoqi.smartpai.service.*;
import com.yizhaoqi.smartpai.parsing.DocumentParsingService;
import com.yizhaoqi.smartpai.parsing.NonPdfDocumentParsingService;
import com.yizhaoqi.smartpai.parsing.description.FigureDescriptionService;
import com.yizhaoqi.smartpai.parsing.persistence.LegacyPermissionContext;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real checkpoint mutations, mocked business stages; no Kafka broker or application startup. */
class FileProcessingConsumerTest {
    @TempDir Path directory;
    private final ParseService parse = mock(ParseService.class);
    private final DocumentParsingService pdfParsing = mock(DocumentParsingService.class);
    private final NonPdfDocumentParsingService nonPdfParsing = mock(NonPdfDocumentParsingService.class);
    private final FigureDescriptionService descriptions = mock(FigureDescriptionService.class);
    private final VectorizationService vectors = mock(VectorizationService.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final FileContentRepository contentRows = mock(FileContentRepository.class);
    private final FileContentProcessingService contents = spy(new FileContentProcessingService(contentRows, org.mockito.Mockito.mock(com.yizhaoqi.smartpai.repository.ProcessingOutboxRepository.class)));
    private final FileUploadRepository files = mock(FileUploadRepository.class);
    private FileContent content;
    private FileProcessingConsumer consumer;
    private FileProcessingTask task;
    private final VectorizationService.VectorizationUsageResult usage =
            new VectorizationService.VectorizationUsageResult(10, 1, "model");

    @BeforeEach
    void setUp() throws Exception {
        consumer = new FileProcessingConsumer(parse, vectors, documents);
        ReflectionTestUtils.setField(consumer, "contentProcessing", contents);
        ReflectionTestUtils.setField(consumer, "files", files);
        ReflectionTestUtils.setField(consumer, "parsingService", pdfParsing);
        ReflectionTestUtils.setField(consumer, "nonPdfParsingService", nonPdfParsing);
        ReflectionTestUtils.setField(consumer, "figureDescriptions", descriptions);
        when(descriptions.describe(anyString(), anyLong())).thenReturn(true);
        Path source = directory.resolve("source.txt");
        Files.writeString(source, "content");
        task = new FileProcessingTask("md5", source.toString(), "source.txt", null, null, false,
                FileProcessingTask.TASK_TYPE_PROCESS_CONTENT, "1");
        task.setObjectPath("merged/md5"); task.setProcessingGeneration(1L); task.setEventId("PROCESS_CONTENT:md5:1");
        content = new FileContent();
        content.setFileMd5("md5"); content.setObjectPath("merged/md5");
        when(contentRows.findByFileMd5("md5")).thenReturn(Optional.of(content));
        when(contentRows.findForUpdate("md5")).thenReturn(Optional.of(content));
        when(nonPdfParsing.parseAndPersist(eq("md5"), eq(1L), any(), any())).thenAnswer(call -> {
            content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
            content.setProcessingError(null); return true;
        });
        FileUpload legacy = new FileUpload();
        legacy.setUserId("1"); legacy.setOrgTag("TEAM_A"); legacy.setPublic(true);
        when(files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1")).thenReturn(Optional.of(legacy));
        when(vectors.vectorizeWithUsage("md5", 1L, "1", "TEAM_A", true, "1")).thenReturn(usage);
    }

    @Test
    void mergedParsesThenCommitsParsedCheckpointBeforeVectorizationAndIndexes() throws Exception {
        consumer.processTask(task);
        var order = inOrder(nonPdfParsing, contents, vectors);
        order.verify(contents).checkpoint("md5", 1);
        order.verify(nonPdfParsing).parseAndPersist(eq("md5"), eq(1L), any(), eq(new LegacyPermissionContext("1", "TEAM_A", true)));
        order.verify(contents, times(2)).checkpoint("md5", 1);
        verify(contents, never()).parsed(anyString(), anyLong());
        verifyNoInteractions(parse);
        order.verify(vectors).vectorizeWithUsage("md5", 1L, "1", "TEAM_A", true, "1");
        order.verify(contents).indexed("md5", 1, usage);
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
        assertNotNull(content.getIndexedAt());
        assertEquals(10L, content.getActualEmbeddingTokens());
        verifyNoInteractions(documents);
        assertNull(task.getUserId());
    }

    @Test
    void parsedSkipsDownloadAndParseAndIndexesDirectly() {
        content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
        content.setProcessingError("previous vector failure");
        task.setFilePath(null);
        consumer.processTask(task);
        verifyNoInteractions(parse, documents);
        verify(vectors).vectorizeWithUsage("md5", 1L, "1", "TEAM_A", true, "1");
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
        assertNull(content.getProcessingError());
    }

    @ParameterizedTest
    @EnumSource(value = FileContent.ProcessingStatus.class, names = {"INDEXED", "FAILED"})
    void terminalCurrentGenerationSkipsAllBusiness(FileContent.ProcessingStatus terminal) {
        content.setProcessingStatus(terminal);
        content.setProcessingError("terminal reason");
        consumer.processTask(task);
        verifyNoInteractions(parse, vectors, files, documents);
        assertEquals(terminal, content.getProcessingStatus());
        assertEquals("terminal reason", content.getProcessingError());
    }

    @Test
    void oldGenerationSkipsBusinessAndDoesNotChangeNewGeneration() {
        content.setProcessingGeneration(2);
        consumer.processTask(task);
        verifyNoInteractions(parse, vectors, files, documents);
        assertEquals(2, content.getProcessingGeneration());
    }

    @Test
    void parseFailureKeepsMergedAndRecordsErrorThenRetryParsesAgain() throws Exception {
        when(nonPdfParsing.parseAndPersist(anyString(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("parse failed")).thenAnswer(call -> {
                    content.setProcessingStatus(FileContent.ProcessingStatus.PARSED); return true;
                });
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.MERGED, content.getProcessingStatus());
        assertEquals("parse failed", content.getProcessingError());
        verify(contents, never()).failed(anyString(), anyLong(), any());
        verifyNoInteractions(vectors, documents);
        consumer.processTask(task);
        verify(nonPdfParsing, times(2)).parseAndPersist(anyString(), anyLong(), any(), any());
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
        assertNull(content.getProcessingError());
    }

    @Test
    void vectorizationFailureKeepsParsedAndRetryNeverParsesAgain() throws Exception {
        when(vectors.vectorizeWithUsage("md5", 1L, "1", "TEAM_A", true, "1"))
                .thenThrow(new IllegalStateException("vector failed")).thenReturn(usage);
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.PARSED, content.getProcessingStatus());
        assertEquals("vector failed", content.getProcessingError());
        verify(contents, never()).failed(anyString(), anyLong(), any());
        consumer.processTask(task);
        verify(nonPdfParsing, times(1)).parseAndPersist(anyString(), anyLong(), any(), any());
        verify(vectors, times(2)).vectorizeWithUsage("md5", 1L, "1", "TEAM_A", true, "1");
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
        assertNull(content.getProcessingError());
    }

    @Test
    void duplicateCompletedMessageSkipsBothStages() throws Exception {
        consumer.processTask(task);
        consumer.processTask(task);
        verify(nonPdfParsing, times(1)).parseAndPersist(anyString(), anyLong(), any(), any());
        verify(vectors, times(1)).vectorizeWithUsage("md5", 1L, "1", "TEAM_A", true, "1");
    }

    @Test
    void invalidIdentityCannotStartBusinessOrChangeContent() {
        task.setEventId("PROCESS_CONTENT:another:1");
        assertThrows(IllegalArgumentException.class, () -> consumer.processTask(task));
        verifyNoInteractions(contentRows, parse, vectors, files, documents);
    }

    @Test
    void checkpointCommitFailureIsPropagatedAndDoesNotProceedToVectorization() throws Exception {
        when(nonPdfParsing.parseAndPersist(anyString(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("checkpoint DB unavailable"));
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.MERGED, content.getProcessingStatus());
        assertEquals("checkpoint DB unavailable", content.getProcessingError());
        verifyNoInteractions(vectors);
    }

    @Test
    void pdfHeaderUsesNewPipelineEvenWithNonPdfFilenameAndDoesNotMarkParsedAgain() throws Exception {
        usePdf("document.txt");
        when(pdfParsing.parseAndPersist(eq("md5"), eq(1L), any(), any())).thenAnswer(invocation -> {
            InputStream input = invocation.getArgument(2);
            assertEquals("%PDF-", new String(input.readNBytes(5), java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(new LegacyPermissionContext("1", "TEAM_A", true), invocation.getArgument(3));
            content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
            content.setProcessingError(null);
            return true;
        });
        consumer.processTask(task);
        verifyNoInteractions(parse);
        verify(contents, never()).parsed(anyString(), anyLong());
        var order = inOrder(pdfParsing, contents, descriptions, vectors);
        order.verify(contents).checkpoint("md5", 1);
        order.verify(pdfParsing).parseAndPersist(eq("md5"), eq(1L), any(), any());
        order.verify(contents).checkpoint("md5", 1);
        order.verify(descriptions).describe("md5", 1);
        order.verify(contents).checkpoint("md5", 1);
        order.verify(vectors).vectorizeWithUsage("md5", 1L, "1", "TEAM_A", true, "1");
        order.verify(contents).indexed("md5", 1, usage);
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
    }

    @Test
    void pdfSuffixWithoutPdfHeaderUsesAtomicNonPdfParser() throws Exception {
        task.setFileName("document.pdf");
        consumer.processTask(task);
        verify(nonPdfParsing).parseAndPersist(eq("md5"), eq(1L), any(), any());
        verifyNoInteractions(parse, pdfParsing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PP submit failed", "PP poll failed", "JSONL decode failed", "Mapper failed",
            "Assembler failed", "Chunk failed", "Figure download failed", "MinIO failed", "DB persistence failed"})
    void pdfStageFailuresKeepMergedRecordErrorAndPropagateToRetry(String message) throws Exception {
        usePdf("document.pdf");
        doThrow(new IOException(message)).when(pdfParsing).parseAndPersist(anyString(), anyLong(), any(), any());
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.MERGED, content.getProcessingStatus());
        assertEquals(message, content.getProcessingError());
        verify(contents, never()).parsed(anyString(), anyLong());
        verify(contents, never()).failed(anyString(), anyLong(), any());
        verifyNoInteractions(parse, vectors, documents);
    }

    @Test
    void generationChangedDuringPdfParsingEndsOldTaskWithoutVectorizationOrRetry() throws Exception {
        usePdf("document.pdf");
        when(pdfParsing.parseAndPersist(anyString(), anyLong(), any(), any())).thenAnswer(invocation -> {
            content.setProcessingGeneration(2);
            content.setProcessingError("new generation error");
            return false;
        });
        assertDoesNotThrow(() -> consumer.processTask(task));
        assertEquals(2, content.getProcessingGeneration());
        assertEquals(FileContent.ProcessingStatus.MERGED, content.getProcessingStatus());
        assertEquals("new generation error", content.getProcessingError());
        verifyNoInteractions(parse, vectors);
        verify(contents, never()).recordError(anyString(), anyLong(), any());
    }

    @ParameterizedTest
    @EnumSource(value = FileContent.ProcessingStatus.class, names = {"INDEXED", "FAILED"})
    void terminalStateReachedDuringPdfParsingSkipsVectorization(FileContent.ProcessingStatus status) throws Exception {
        usePdf("document.pdf");
        when(pdfParsing.parseAndPersist(anyString(), anyLong(), any(), any())).thenAnswer(invocation -> {
            content.setProcessingStatus(status);
            return false;
        });
        consumer.processTask(task);
        assertEquals(status, content.getProcessingStatus());
        verifyNoInteractions(parse, vectors);
        verify(contents, never()).parsed(anyString(), anyLong());
    }

    @Test
    void anotherTaskCommittedParsedCanReuseItsDurableArtifacts() throws Exception {
        usePdf("document.pdf");
        when(pdfParsing.parseAndPersist(anyString(), anyLong(), any(), any())).thenAnswer(invocation -> {
            content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
            return false;
        });
        consumer.processTask(task);
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
        verifyNoInteractions(parse);
        verify(contents, never()).parsed(anyString(), anyLong());
    }

    @Test
    void pdfResultWithoutParsedCheckpointCannotStartVectorization() throws Exception {
        usePdf("document.pdf");
        when(pdfParsing.parseAndPersist(anyString(), anyLong(), any(), any())).thenReturn(true);
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.MERGED, content.getProcessingStatus());
        assertTrue(content.getProcessingError().contains("did not reach PARSED"));
        verifyNoInteractions(vectors);
    }

    @Test
    void pdfVectorizationFailureRetriesFromParsedWithoutCallingPpAgain() throws Exception {
        usePdf("document.pdf");
        when(pdfParsing.parseAndPersist(anyString(), anyLong(), any(), any())).thenAnswer(invocation -> {
            content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
            return true;
        });
        when(vectors.vectorizeWithUsage("md5", 1L, "1", "TEAM_A", true, "1"))
                .thenThrow(new IllegalStateException("vector failed")).thenReturn(usage);
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.PARSED, content.getProcessingStatus());
        assertEquals("vector failed", content.getProcessingError());
        task.setFilePath(null); // PARSED retry must not download anything.
        consumer.processTask(task);
        verify(pdfParsing, times(1)).parseAndPersist(anyString(), anyLong(), any(), any());
        verifyNoInteractions(parse);
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
        assertNull(content.getProcessingError());
    }

    @Test
    void alreadyParsedPdfDoesNotCallPpOrDownload() throws Exception {
        usePdf("document.pdf");
        content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
        task.setFilePath(null);
        consumer.processTask(task);
        verifyNoInteractions(parse, pdfParsing);
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
    }

    @Test
    void disabledPpRecordsClearConfigurationErrorAndDoesNotFallBack() throws Exception {
        usePdf("document.pdf");
        DocumentParsingService disabled = new DocumentParsingService(
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(com.yizhaoqi.smartpai.parsing.pp.PpStructureApiClient.class),
                new com.yizhaoqi.smartpai.parsing.chunk.ParsedDocumentChunker(
                        new com.yizhaoqi.smartpai.parsing.chunk.TextChunker(512, 100, 100)),
                mock(com.yizhaoqi.smartpai.parsing.persistence.DocumentParsingPersistenceCoordinator.class));
        ReflectionTestUtils.setField(consumer, "parsingService", disabled);
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.MERGED, content.getProcessingStatus());
        assertTrue(content.getProcessingError().contains("PP-StructureV3 disabled"));
        verifyNoInteractions(parse, vectors);
    }

    private void usePdf(String filename) throws Exception {
        Path pdf = directory.resolve("actual-pdf.bin");
        Files.writeString(pdf, "%PDF-1.7\nFake PDF for routing tests");
        task.setFilePath(pdf.toString());
        task.setFileName(filename);
    }

    @Test
    void contentTaskPassesItsActualGenerationToVectorization() {
        content.setProcessingGeneration(7);
        content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
        task.setProcessingGeneration(7L);
        task.setEventId("PROCESS_CONTENT:md5:7");
        task.setFilePath(null);
        when(vectors.vectorizeWithUsage("md5", 7L, "1", "TEAM_A", true, "1")).thenReturn(usage);
        consumer.processTask(task);
        verify(vectors).vectorizeWithUsage("md5", 7L, "1", "TEAM_A", true, "1");
        verify(vectors, never()).vectorizeWithUsage(anyString(), anyString(), anyString(), anyBoolean(), anyString());
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
    }

    @Test
    void processContentBulkFailureRetriesParsedWithTheSameStableEsIds() {
        DocumentVectorRepository textRows = mock(DocumentVectorRepository.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        ElasticsearchService es = mock(ElasticsearchService.class);
        DocumentVector row = new DocumentVector();
        row.setChunkId(1); row.setTextContent("body");
        when(textRows.findByFileMd5OrderByChunkIdAsc("md5")).thenReturn(List.of(row));
        when(embedding.embedWithUsage(List.of("body"), "1", EmbeddingClient.UsageType.UPLOAD))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(List.of(new float[]{1, 2}), 10, "model"));
        doThrow(new IllegalStateException("partial bulk failure")).doNothing().when(es).bulkIndex(anyList());
        VectorizationService actualVectors = new VectorizationService();
        ReflectionTestUtils.setField(actualVectors, "documentVectorRepository", textRows);
        ReflectionTestUtils.setField(actualVectors, "embeddingClient", embedding);
        ReflectionTestUtils.setField(actualVectors, "elasticsearchService", es);
        ReflectionTestUtils.setField(actualVectors, "fileContentRepository", contentRows);
        ReflectionTestUtils.setField(actualVectors, "documentFigureRepository", mock(DocumentFigureRepository.class));
        var aclFiles = mock(FileUploadRepository.class);
        var aclRelation = new FileUpload(); aclRelation.setUserId("1"); aclRelation.setStatus(FileUpload.STATUS_COMPLETED);
        when(aclFiles.findAllByFileMd5("md5")).thenReturn(List.of(aclRelation));
        ReflectionTestUtils.setField(actualVectors, "contentIndexWriter", new com.yizhaoqi.smartpai.service.ContentIndexWriter(contentRows, aclFiles, es));
        ReflectionTestUtils.setField(consumer, "vectorizationService", actualVectors);
        content.setProcessingGeneration(7);
        content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
        task.setProcessingGeneration(7L); task.setEventId("PROCESS_CONTENT:md5:7"); task.setFilePath(null);

        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.PARSED, content.getProcessingStatus());
        consumer.processTask(task);
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
        assertNull(content.getProcessingError());
        verifyNoInteractions(parse, pdfParsing);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<EsDocument>> batches = ArgumentCaptor.forClass((Class) List.class);
        verify(es, times(2)).bulkIndex(batches.capture());
        assertEquals("TEXT:md5:7:1", batches.getAllValues().get(0).get(0).getId());
        assertEquals(batches.getAllValues().get(0).get(0).getId(), batches.getAllValues().get(1).get(0).getId());
    }

    @Test
    void legacyUploadStillUsesTheOriginalVectorizationEntry() {
        task.setTaskType(FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS);
        task.setUserId("1"); task.setOrgTag("TEAM_A"); task.setPublic(true);
        when(vectors.vectorizeWithUsage("md5", "1", "TEAM_A", true, "1")).thenReturn(usage);
        consumer.processTask(task);
        verify(vectors).vectorizeWithUsage("md5", "1", "TEAM_A", true, "1");
        verify(vectors, never()).vectorizeWithUsage(anyString(), anyLong(), anyString(), anyString(), anyBoolean(), anyString());
        verify(documents).markVectorizationCompleted("md5", usage);
        verifyNoInteractions(contentRows);
    }

    @Test
    void delayedLegacyUploadCannotAppendToCurrentContentArtifacts() {
        task.setTaskType(FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS);
        when(documents.isLegacyUploadSuperseded("md5")).thenReturn(true);
        consumer.processTask(task);
        verify(documents).isLegacyUploadSuperseded("md5");
        verify(documents, never()).markVectorizationProcessing(anyString(), anyBoolean());
        verifyNoInteractions(parse, pdfParsing, vectors, descriptions, contents, files);
    }

    @Test
    void legacyReindexStillDelegatesToDocumentService() {
        task.setTaskType(FileProcessingTask.TASK_TYPE_REINDEX);
        task.setUserId("1");
        consumer.processTask(task);
        verify(documents).reindexDocument("md5", "1");
        verifyNoInteractions(vectors, parse, pdfParsing, contentRows, descriptions);
    }

    @Test
    void parsedPreparesDescriptionsBeforeIndexAndCompletesOnlyAfterSuccess() throws Exception {
        content.setProcessingStatus(FileContent.ProcessingStatus.PARSED); task.setFilePath(null);
        consumer.processTask(task);
        var order = inOrder(descriptions, vectors, contents);
        order.verify(descriptions).describe("md5", 1);
        order.verify(vectors).vectorizeWithUsage("md5", 1L, "1", "TEAM_A", true, "1");
        order.verify(contents).indexed("md5", 1, usage);
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
    }

    @Test
    void descriptionFailureKeepsParsedAndRetryDoesNotReparse() throws Exception {
        content.setProcessingStatus(FileContent.ProcessingStatus.PARSED); task.setFilePath(null);
        when(descriptions.describe("md5", 1)).thenThrow(new IOException("description unavailable")).thenReturn(true);
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.PARSED, content.getProcessingStatus());
        assertEquals("description unavailable", content.getProcessingError());
        verifyNoInteractions(vectors, parse, pdfParsing);
        consumer.processTask(task);
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
        verifyNoInteractions(parse, pdfParsing);
    }

    @Test
    void staleDescriptionResultStopsIndexingWithoutCommittingIndexed() throws Exception {
        content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
        when(descriptions.describe("md5", 1)).thenReturn(false);
        consumer.processTask(task);
        assertEquals(FileContent.ProcessingStatus.PARSED, content.getProcessingStatus());
        verifyNoInteractions(vectors, parse, pdfParsing);
    }

    @Test
    void noIndexableArtifactsCannotCompleteIndexed() {
        content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
        when(vectors.vectorizeWithUsage("md5", 1L, "1", "TEAM_A", true, "1"))
                .thenReturn(new VectorizationService.VectorizationUsageResult(0, 0, "model"));
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.PARSED, content.getProcessingStatus());
        assertNull(content.getIndexedAt());
    }
}
