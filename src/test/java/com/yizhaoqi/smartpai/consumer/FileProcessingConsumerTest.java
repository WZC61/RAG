package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import com.yizhaoqi.smartpai.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real checkpoint mutations, mocked business stages; no Kafka broker or application startup. */
class FileProcessingConsumerTest {
    @TempDir Path directory;
    private final ParseService parse = mock(ParseService.class);
    private final VectorizationService vectors = mock(VectorizationService.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final FileContentRepository contentRows = mock(FileContentRepository.class);
    private final FileContentProcessingService contents = spy(new FileContentProcessingService(contentRows));
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
        Path source = directory.resolve("source.txt");
        Files.writeString(source, "content");
        task = new FileProcessingTask("md5", source.toString(), "source.txt", null, null, false,
                FileProcessingTask.TASK_TYPE_PROCESS_CONTENT, "1");
        task.setObjectPath("merged/md5"); task.setProcessingGeneration(1L); task.setEventId("PROCESS_CONTENT:md5:1");
        content = new FileContent();
        content.setFileMd5("md5"); content.setObjectPath("merged/md5");
        when(contentRows.findByFileMd5("md5")).thenReturn(Optional.of(content));
        when(contentRows.findForUpdate("md5")).thenReturn(Optional.of(content));
        FileUpload legacy = new FileUpload();
        legacy.setUserId("1"); legacy.setOrgTag("TEAM_A"); legacy.setPublic(true);
        when(files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1")).thenReturn(Optional.of(legacy));
        when(vectors.vectorizeWithUsage("md5", "1", "TEAM_A", true, "1")).thenReturn(usage);
    }

    @Test
    void mergedParsesThenCommitsParsedCheckpointBeforeVectorizationAndIndexes() throws Exception {
        consumer.processTask(task);
        var order = inOrder(parse, contents, vectors);
        order.verify(contents).checkpoint("md5", 1);
        order.verify(parse).parseAndSave(eq("md5"), any(), eq("1"), eq("TEAM_A"), eq(true));
        order.verify(contents).parsed("md5", 1);
        order.verify(vectors).vectorizeWithUsage("md5", "1", "TEAM_A", true, "1");
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
        verify(vectors).vectorizeWithUsage("md5", "1", "TEAM_A", true, "1");
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
        doThrow(new IllegalStateException("parse failed")).doNothing().when(parse)
                .parseAndSave(anyString(), any(), anyString(), anyString(), anyBoolean());
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.MERGED, content.getProcessingStatus());
        assertEquals("parse failed", content.getProcessingError());
        verify(contents, never()).failed(anyString(), anyLong(), any());
        verifyNoInteractions(vectors, documents);
        consumer.processTask(task);
        verify(parse, times(2)).parseAndSave(anyString(), any(), anyString(), anyString(), anyBoolean());
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
        assertNull(content.getProcessingError());
    }

    @Test
    void vectorizationFailureKeepsParsedAndRetryNeverParsesAgain() throws Exception {
        when(vectors.vectorizeWithUsage("md5", "1", "TEAM_A", true, "1"))
                .thenThrow(new IllegalStateException("vector failed")).thenReturn(usage);
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.PARSED, content.getProcessingStatus());
        assertEquals("vector failed", content.getProcessingError());
        verify(contents, never()).failed(anyString(), anyLong(), any());
        consumer.processTask(task);
        verify(parse, times(1)).parseAndSave(anyString(), any(), anyString(), anyString(), anyBoolean());
        verify(vectors, times(2)).vectorizeWithUsage("md5", "1", "TEAM_A", true, "1");
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
        assertNull(content.getProcessingError());
    }

    @Test
    void duplicateCompletedMessageSkipsBothStages() throws Exception {
        consumer.processTask(task);
        consumer.processTask(task);
        verify(parse, times(1)).parseAndSave(anyString(), any(), anyString(), anyString(), anyBoolean());
        verify(vectors, times(1)).vectorizeWithUsage("md5", "1", "TEAM_A", true, "1");
    }

    @Test
    void invalidIdentityCannotStartBusinessOrChangeContent() {
        task.setEventId("PROCESS_CONTENT:another:1");
        assertThrows(IllegalArgumentException.class, () -> consumer.processTask(task));
        verifyNoInteractions(contentRows, parse, vectors, files, documents);
    }

    @Test
    void checkpointCommitFailureIsPropagatedAndDoesNotProceedToVectorization() {
        doThrow(new IllegalStateException("checkpoint DB unavailable")).when(contents).parsed("md5", 1);
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.MERGED, content.getProcessingStatus());
        assertEquals("checkpoint DB unavailable", content.getProcessingError());
        verifyNoInteractions(vectors);
    }
}
