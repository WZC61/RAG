package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.controller.UploadController;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import io.minio.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real JPA transactions and SQL constraints, no running application or external infrastructure. */
class ProcessingOutboxTest {
    private static LocalContainerEntityManagerFactoryBean factory;
    private static JpaTransactionManager manager;
    private static TransactionTemplate transaction;
    private static FileUploadRepository files;
    private static ChunkInfoRepository chunks;
    private static ProcessingOutboxRepository outbox;
    private static FileContentRepository contents;
    private final ObjectMapper mapper = new ObjectMapper();
    private UploadCompletionService completion;
    private ProcessingOutboxStatusService status;
    private KafkaTemplate<String, Object> kafka;
    private KafkaConfig config;
    private UploadService urls;
    private ProcessingOutboxDispatcher dispatcher;

    @BeforeAll
    static void createDatabase() {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:processing-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        factory.setManagedTypes(PersistenceManagedTypes.of(FileUpload.class.getName(), ChunkInfo.class.getName(), ProcessingOutbox.class.getName(), FileContent.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        manager = new JpaTransactionManager(factory.getObject());
        transaction = new TransactionTemplate(manager);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
        repositories.addRepositoryProxyPostProcessor((proxy, information) -> {
            proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect()));
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        });
        files = repositories.getRepository(FileUploadRepository.class);
        chunks = repositories.getRepository(ChunkInfoRepository.class);
        outbox = repositories.getRepository(ProcessingOutboxRepository.class);
        contents = repositories.getRepository(FileContentRepository.class);
    }

    @AfterAll
    static void closeDatabase() { if (factory != null) factory.destroy(); }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        transaction.executeWithoutResult(tx -> {
            outbox.deleteAllInBatch();
            contents.deleteAllInBatch();
            chunks.deleteAllInBatch();
            files.deleteAllInBatch();
        });
        createFile("1");
        completion = proxy(new UploadCompletionService(files, contents, outbox, mapper));
        status = proxy(new ProcessingOutboxStatusService(outbox));
        kafka = mock(KafkaTemplate.class);
        when(kafka.executeInTransaction(any())).thenAnswer(invocation -> {
            KafkaOperations.OperationsCallback<String, Object, Boolean> callback = invocation.getArgument(0);
            return callback.doInOperations(kafka);
        });
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        config = mock(KafkaConfig.class);
        when(config.getFileProcessingTopic()).thenReturn("file-processing-topic1");
        urls = mock(UploadService.class);
        when(urls.generateMergedObjectUrl(anyString())).thenReturn("https://storage/fresh-url");
        dispatcher = new ProcessingOutboxDispatcher(processOnlyRepository(), status, kafka, config, mapper, urls);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private void createFile(String userId) {
        transaction.executeWithoutResult(tx -> {
            FileUpload file = new FileUpload();
            file.setUserId(userId);
            file.setFileMd5("md5");
            file.setFileName("test.pdf");
            file.setTotalSize(3L);
            file.setOrgTag("TEAM_A");
            file.setStatus(FileUpload.STATUS_MERGING);
            files.saveAndFlush(file);
        });
    }

    @Test
    void completionCommitsFileAndPendingEventTogetherWithoutKafka() throws Exception {
        completion.complete("1", "md5");
        FileUpload file = file("1");
        assertEquals(FileUpload.STATUS_COMPLETED, file.getStatus());
        assertNotNull(file.getMergedAt());
        assertNull(file.getVectorizationStatus());
        assertEquals(1, contents.count());
        FileContent content = contents.findByFileMd5("md5").orElseThrow();
        assertEquals(FileContent.ProcessingStatus.MERGED, content.getProcessingStatus());
        assertEquals(1, content.getProcessingGeneration());
        assertEquals("merged/md5", content.getObjectPath());
        assertEquals(3L, content.getTotalSize());
        assertNotNull(content.getCreatedAt());
        assertNotNull(content.getUpdatedAt());
        ProcessingOutbox event = event("1");
        assertEquals(ProcessingOutbox.Status.PENDING, event.getStatus());
        assertNotNull(event.getCreatedAt());
        assertNull(event.getSentAt());
        verifyNoInteractions(kafka, urls);
    }

    private UploadCompletionService failingCompletion() {
        ProcessingOutboxRepository failing = mock(ProcessingOutboxRepository.class, org.mockito.AdditionalAnswers.delegatesTo(outbox));
        doAnswer(invocation -> {
            ProcessingOutbox event = invocation.getArgument(0);
            event.setPayload(null); // Real non-null constraint failure after file saveAndFlush.
            return outbox.saveAndFlush(event);
        }).when(failing).saveAndFlush(any());
        return proxy(new UploadCompletionService(files, contents, failing, mapper));
    }

    @Test
    void outboxConstraintFailureRollsBackAlreadyFlushedFileUpdate() {
        assertThrows(Exception.class, () -> failingCompletion().complete("1", "md5"));
        assertEquals(FileUpload.STATUS_MERGING, file("1").getStatus());
        assertNull(file("1").getVectorizationStatus());
        assertEquals(0, outbox.findAll().stream().filter(e -> FileProcessingTask.TASK_TYPE_PROCESS_CONTENT.equals(e.getEventType())).count());
        assertEquals(0, contents.count());
    }

    @Test
    void duplicateCompletionKeepsOneEventAndDoesNotResetConsumerState() throws Exception {
        completion.complete("1", "md5");
        ProcessingOutbox first = event("1");
        transaction.executeWithoutResult(tx -> {
            FileUpload file = file("1");
            file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_COMPLETED);
            file.setActualEmbeddingTokens(20L);
            files.saveAndFlush(file);
        });
        completion.complete("1", "md5");
        assertEquals(1, outbox.findAll().stream().filter(e -> FileProcessingTask.TASK_TYPE_PROCESS_CONTENT.equals(e.getEventType())).count());
        assertEquals(1, contents.count());
        assertEquals(first.getId(), event("1").getId());
        assertEquals(FileUpload.VECTORIZATION_STATUS_COMPLETED, file("1").getVectorizationStatus());
        assertEquals(20L, file("1").getActualEmbeddingTokens());
    }

    @Test
    void differentUsersShareOneContentAndStableEvent() throws Exception {
        createFile("2");
        completion.complete("1", "md5");
        completion.complete("2", "md5");
        assertEquals(1, outbox.findAll().stream().filter(e -> FileProcessingTask.TASK_TYPE_PROCESS_CONTENT.equals(e.getEventType())).count());
        assertEquals(1, contents.count());
        assertEquals(2, files.count());
        assertEquals(FileUpload.STATUS_COMPLETED, file("2").getStatus());
        assertEquals("PROCESS_CONTENT:md5:1", event("1").getEventId());
        assertNull(event("1").getUserId());
    }

    @Test
    void payloadContainsStableObjectPathAndNeverPresignedUrl() throws Exception {
        completion.complete("1", "md5");
        String json = event("1").getPayload();
        var payload = mapper.readValue(json, ProcessingOutboxPayload.class);
        assertEquals("merged/md5", payload.objectPath());
        assertEquals(1, payload.processingGeneration());
        assertEquals("1", payload.requesterId()); // Billing initiator, not ACL or content identity.
        assertFalse(json.contains("\"userId\""));
        assertFalse(json.contains("orgTag"));
        assertFalse(json.contains("isPublic"));
        assertEquals("test.pdf", payload.fileName());
        assertFalse(json.contains("http"));
        assertFalse(json.contains("X-Amz"));
        verifyNoInteractions(urls);
    }

    @Test
    void successfulKafkaCommitMarksSentAndTaskCarriesEventIdAndFreshUrl() throws Exception {
        completion.complete("1", "md5");
        dispatcher.dispatchPending();
        assertEquals(ProcessingOutbox.Status.SENT, event("1").getStatus());
        assertNotNull(event("1").getSentAt());
        ArgumentCaptor<FileProcessingTask> task = ArgumentCaptor.forClass(FileProcessingTask.class);
        verify(kafka).send(eq("file-processing-topic1"), eq("md5"), task.capture());
        assertEquals(event("1").getEventId(), task.getValue().getEventId());
        assertEquals("https://storage/fresh-url", task.getValue().getFilePath());
        assertNull(task.getValue().getUserId());
        assertNull(task.getValue().getOrgTag());
        assertEquals("merged/md5", task.getValue().getObjectPath());
        assertEquals(1L, task.getValue().getProcessingGeneration());
        assertEquals(FileProcessingTask.TASK_TYPE_PROCESS_CONTENT, task.getValue().getTaskType());
        dispatcher.dispatchPending();
        verify(kafka, times(1)).executeInTransaction(any());
    }

    @Test
    void unavailableKafkaLeavesDurablePendingAndCountsFailure() throws Exception {
        completion.complete("1", "md5");
        doThrow(new IllegalStateException("broker unavailable")).when(kafka).executeInTransaction(any());
        dispatcher.dispatchPending();
        ProcessingOutbox event = event("1");
        assertEquals(FileUpload.STATUS_COMPLETED, file("1").getStatus());
        assertEquals(ProcessingOutbox.Status.PENDING, event.getStatus());
        assertEquals(1, event.getRetryCount());
        assertTrue(event.getLastError().contains("broker unavailable"));
        assertNull(event.getSentAt());
    }

    @Test
    void oneFailedEventDoesNotBlockOtherEventsInBatch() throws Exception {
        createOtherContent();
        completion.complete("1", "md5");
        completion.complete("2", "other");
        doThrow(new IllegalStateException("one task fails")).when(kafka)
                .send(anyString(), eq("md5"), any());
        dispatcher.dispatchPending();
        assertEquals(ProcessingOutbox.Status.PENDING, event("1").getStatus());
        assertEquals(1, event("1").getRetryCount());
        assertEquals(ProcessingOutbox.Status.SENT, otherEvent().getStatus());
    }

    @Test
    void retryEventuallySucceedsWithSameEventAndNewUrl() throws Exception {
        completion.complete("1", "md5");
        when(urls.generateMergedObjectUrl("md5")).thenReturn("https://storage/attempt-1", "https://storage/attempt-2");
        doThrow(new IllegalStateException("temporary")).doReturn(true).when(kafka).executeInTransaction(any());
        dispatcher.dispatchPending();
        assertEquals(ProcessingOutbox.Status.PENDING, event("1").getStatus());
        // Restore callback execution; a new dispatcher instance models recovery after process restart.
        doAnswer(invocation -> {
            KafkaOperations.OperationsCallback<String, Object, Boolean> callback = invocation.getArgument(0);
            return callback.doInOperations(kafka);
        }).when(kafka).executeInTransaction(any());
        new ProcessingOutboxDispatcher(processOnlyRepository(), status, kafka, config, mapper, urls).dispatchPending();
        assertEquals(ProcessingOutbox.Status.SENT, event("1").getStatus());
        assertEquals(1, event("1").getRetryCount());
        assertNull(event("1").getLastError());
        verify(kafka).send(eq("file-processing-topic1"), eq("md5"),
                argThat(task -> ((FileProcessingTask) task).getFilePath().equals("https://storage/attempt-2")));
    }

    @Test
    void brokerCommitFailureAfterSendNeverMarksSent() throws Exception {
        completion.complete("1", "md5");
        doAnswer(invocation -> {
            KafkaOperations.OperationsCallback<String, Object, Boolean> callback = invocation.getArgument(0);
            callback.doInOperations(kafka);
            throw new IllegalStateException("transaction commit failed");
        }).when(kafka).executeInTransaction(any());
        dispatcher.dispatchPending();
        verify(kafka).send(anyString(), anyString(), any());
        assertEquals(ProcessingOutbox.Status.PENDING, event("1").getStatus());
    }

    @Test
    void kafkaSuccessButSentDatabaseFailureCausesReplayWithSameEventId() throws Exception {
        completion.complete("1", "md5");
        ProcessingOutboxStatusService failing = mock(ProcessingOutboxStatusService.class);
        doThrow(new IllegalStateException("database unavailable")).when(failing).markSent(anyLong());
        new ProcessingOutboxDispatcher(processOnlyRepository(), failing, kafka, config, mapper, urls).dispatchPending();
        assertEquals(ProcessingOutbox.Status.PENDING, event("1").getStatus());
        dispatcher.dispatchPending();
        verify(kafka, times(2)).send(eq("file-processing-topic1"), eq("md5"), any());
        assertEquals(ProcessingOutbox.Status.SENT, event("1").getStatus());
    }

    @Test
    void batchIsBoundedAndScannedByAscendingId() throws Exception {
        createOtherContent();
        completion.complete("1", "md5");
        completion.complete("2", "other");
        ReflectionTestUtils.setField(dispatcher, "batchSize", 1);
        dispatcher.dispatchPending();
        assertEquals(ProcessingOutbox.Status.SENT, event("1").getStatus());
        assertEquals(ProcessingOutbox.Status.PENDING, otherEvent().getStatus());
        dispatcher.dispatchPending();
        assertEquals(ProcessingOutbox.Status.SENT, otherEvent().getStatus());
    }

    @Test
    void completionFailureRetainsChunksAndMergeRetryReusesExistingObject() throws Exception {
        files.updateStatusIfCurrent(file("1").getId(), FileUpload.STATUS_MERGING, FileUpload.STATUS_UPLOADING);
        transaction.executeWithoutResult(tx -> {
            ChunkInfo chunk = new ChunkInfo();
            chunk.setUserId("1"); chunk.setFileMd5("md5"); chunk.setChunkIndex(0);
            chunk.setChunkMd5("checksum"); chunk.setStoragePath("chunks/1/md5/0");
            chunks.saveAndFlush(chunk);
        });
        MinioClient minio = mock(MinioClient.class);
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(3L);
        when(minio.statObject(any())).thenReturn(stat); // Both source and valid merged object already exist.
        when(minio.getPresignedObjectUrl(any())).thenReturn("https://storage/fresh");
        UploadService uploads = new UploadService();
        ReflectionTestUtils.setField(uploads, "fileUploadRepository", files);
        ReflectionTestUtils.setField(uploads, "fileContentRepository", contents);
        ReflectionTestUtils.setField(uploads, "chunkInfoRepository", chunks);
        ReflectionTestUtils.setField(uploads, "minioClient", minio);
        ReflectionTestUtils.setField(uploads, "uploadCompletionService", failingCompletion());
        UploadController controller = new UploadController(uploads);
        ReflectionTestUtils.setField(controller, "fileUploadRepository", files);
        ReflectionTestUtils.setField(controller, "fileContentRepository", contents);
        ReflectionTestUtils.setField(controller, "parseService", mock(ParseService.class));
        var request = new UploadController.MergeRequest("md5", "test.pdf");
        assertEquals(503, controller.mergeFile(request, "1").getStatusCode().value());
        assertEquals(FileUpload.STATUS_UPLOADING, file("1").getStatus());
        assertEquals(0, outbox.findAll().stream().filter(e -> FileProcessingTask.TASK_TYPE_PROCESS_CONTENT.equals(e.getEventType())).count());
        assertEquals(1, chunks.count());
        verify(minio, never()).removeObject(any());
        ReflectionTestUtils.setField(uploads, "uploadCompletionService", completion);
        assertEquals(200, controller.mergeFile(request, "1").getStatusCode().value());
        assertEquals(1, outbox.findAll().stream().filter(e -> FileProcessingTask.TASK_TYPE_PROCESS_CONTENT.equals(e.getEventType())).count());
        assertEquals(0, chunks.count());
        verify(minio, never()).composeObject(any());
        verifyNoInteractions(kafka);
    }

    private ProcessingOutboxRepository processOnlyRepository() {
        var repository = mock(ProcessingOutboxRepository.class);
        when(repository.findByStatusOrderByIdAsc(any(), any())).thenAnswer(invocation ->
                outbox.findAll().stream().filter(e -> FileProcessingTask.TASK_TYPE_PROCESS_CONTENT.equals(e.getEventType()))
                        .filter(e -> e.getStatus() == invocation.getArgument(0))
                        .sorted(java.util.Comparator.comparing(ProcessingOutbox::getId))
                        .limit(invocation.<org.springframework.data.domain.Pageable>getArgument(1).getPageSize()).toList());
        return repository;
    }

    private FileUpload file(String userId) {
        return files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", userId).orElseThrow();
    }
    private ProcessingOutbox event(String userId) {
        return outbox.findByEventId(UploadCompletionService.initialEventId("md5", 1)).orElseThrow();
    }

    private void createOtherContent() {
        createFile("2");
        transaction.executeWithoutResult(tx -> {
            FileUpload file = file("2");
            file.setFileMd5("other");
            files.saveAndFlush(file);
        });
    }
    private ProcessingOutbox otherEvent() {
        return outbox.findByEventId(UploadCompletionService.initialEventId("other", 1)).orElseThrow();
    }

    @Test
    void simultaneousFirstCompletionsSerializeOnUniqueContent() throws Exception {
        createFile("2");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = workers.submit(() -> { start.await(); completion.complete("1", "md5"); return null; });
            Future<?> b = workers.submit(() -> { start.await(); completion.complete("2", "md5"); return null; });
            start.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
            assertEquals(1, contents.count());
            assertEquals(1, outbox.findAll().stream().filter(e -> FileProcessingTask.TASK_TYPE_PROCESS_CONTENT.equals(e.getEventType())).count());
            assertEquals(2, files.count());
            assertEquals(FileUpload.STATUS_COMPLETED, file("1").getStatus());
            assertEquals(FileUpload.STATUS_COMPLETED, file("2").getStatus());
        } finally { workers.shutdownNow(); }
    }

    @Test
    void indexedContentCompletesSecondUserWithoutAnyNewEvent() throws Exception {
        completion.complete("1", "md5");
        transaction.executeWithoutResult(tx -> {
            FileContent content = contents.findByFileMd5("md5").orElseThrow();
            content.setProcessingStatus(FileContent.ProcessingStatus.INDEXED);
            contents.saveAndFlush(content);
            outbox.deleteAllInBatch(); // INDEXED is sufficient, even without retained historical events.
        });
        createFile("2");
        files.updateStatusIfCurrent(file("2").getId(), FileUpload.STATUS_MERGING, FileUpload.STATUS_UPLOADING);
        completion.completeInstantUpload("2", "md5");
        assertEquals(FileUpload.STATUS_COMPLETED, file("2").getStatus());
        assertEquals(1, contents.count());
        assertEquals(0, outbox.findAll().stream().filter(e -> FileProcessingTask.TASK_TYPE_PROCESS_CONTENT.equals(e.getEventType())).count());
    }

    @Test
    void parsedContentReusesCurrentGenerationEventAndState() throws Exception {
        completion.complete("1", "md5");
        transaction.executeWithoutResult(tx -> {
            FileContent content = contents.findByFileMd5("md5").orElseThrow();
            content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
            contents.saveAndFlush(content);
        });
        createFile("2");
        files.updateStatusIfCurrent(file("2").getId(), FileUpload.STATUS_MERGING, FileUpload.STATUS_UPLOADING);
        completion.completeInstantUpload("2", "md5");
        assertEquals(1, outbox.findAll().stream().filter(e -> FileProcessingTask.TASK_TYPE_PROCESS_CONTENT.equals(e.getEventType())).count());
        assertEquals(FileContent.ProcessingStatus.PARSED, contents.findByFileMd5("md5").orElseThrow().getProcessingStatus());
    }

    @Test
    void processingStateAndUsageAreStoredOnlyOnContentAndStaleGenerationCannotOverwrite() throws Exception {
        completion.complete("1", "md5");
        FileContentProcessingService processing = proxy(new FileContentProcessingService(contents, org.mockito.Mockito.mock(com.yizhaoqi.smartpai.repository.ProcessingOutboxRepository.class)));
        assertTrue(processing.needsProcessing("md5", 1));
        contents.updateEstimates("md5", 30L, 2);
        processing.parsed("md5", 1);
        assertEquals(FileContent.ProcessingStatus.PARSED, contents.findByFileMd5("md5").orElseThrow().getProcessingStatus());
        processing.recordError("md5", 1, new IOException("temporary failure"));
        assertEquals(FileContent.ProcessingStatus.PARSED, contents.findByFileMd5("md5").orElseThrow().getProcessingStatus());
        assertEquals("temporary failure", contents.findByFileMd5("md5").orElseThrow().getProcessingError());
        processing.indexed("md5", 1, new VectorizationService.VectorizationUsageResult(20, 2, "model"));
        FileContent content = contents.findByFileMd5("md5").orElseThrow();
        assertEquals(FileContent.ProcessingStatus.INDEXED, content.getProcessingStatus());
        assertNull(content.getProcessingError());
        assertEquals(20L, content.getActualEmbeddingTokens());
        assertEquals(30L, content.getEstimatedEmbeddingTokens());
        assertNotNull(content.getIndexedAt());
        assertNull(file("1").getActualEmbeddingTokens());
        assertNull(file("1").getVectorizationStatus());
        assertFalse(processing.needsProcessing("md5", 1));
        processing.failed("md5", 0, new IOException("stale"));
        processing.failed("md5", 1, new IOException("late failure"));
        assertEquals(FileContent.ProcessingStatus.INDEXED, contents.findByFileMd5("md5").orElseThrow().getProcessingStatus());
    }

    @Test
    void terminalFailureIsDurableAndSameGenerationCannotResumeOrEraseFinalError() throws Exception {
        completion.complete("1", "md5");
        FileContentProcessingService processing = proxy(new FileContentProcessingService(contents, org.mockito.Mockito.mock(com.yizhaoqi.smartpai.repository.ProcessingOutboxRepository.class)));
        processing.recordError("md5", 1, new IOException("parse retry"));
        assertEquals(FileContent.ProcessingStatus.MERGED, processing.checkpoint("md5", 1));
        processing.parsed("md5", 1);
        assertNull(contents.findByFileMd5("md5").orElseThrow().getProcessingError());
        processing.failed("md5", 1, new RuntimeException("listener failure", new IOException("final failure")));
        assertEquals(FileContent.ProcessingStatus.FAILED, processing.checkpoint("md5", 1));
        assertFalse(processing.needsProcessing("md5", 1));
        processing.parsed("md5", 1);
        processing.indexed("md5", 1, new VectorizationService.VectorizationUsageResult(20, 2, "model"));
        processing.recordError("md5", 1, new IOException("late retry error"));
        FileContent stored = contents.findByFileMd5("md5").orElseThrow();
        assertEquals(FileContent.ProcessingStatus.FAILED, stored.getProcessingStatus());
        assertEquals("final failure", stored.getProcessingError());
        assertNull(stored.getIndexedAt());
        assertNull(processing.checkpoint("md5", 0));
    }

    @Test
    void differentGenerationsOfSameContentUseSameKafkaKeyAndDistinctEventIds() throws Exception {
        completion.complete("1", "md5");
        dispatcher.dispatchPending();
        transaction.executeWithoutResult(tx -> {
            FileContent content = contents.findByFileMd5("md5").orElseThrow();
            content.setProcessingGeneration(2);
            contents.saveAndFlush(content);
        });
        completion.complete("1", "md5");
        dispatcher.dispatchPending();
        ArgumentCaptor<FileProcessingTask> tasks = ArgumentCaptor.forClass(FileProcessingTask.class);
        verify(kafka, times(2)).send(eq("file-processing-topic1"), eq("md5"), tasks.capture());
        assertEquals(List.of("PROCESS_CONTENT:md5:1", "PROCESS_CONTENT:md5:2"),
                tasks.getAllValues().stream().map(FileProcessingTask::getEventId).toList());
        assertEquals(2, outbox.findAll().stream().filter(e -> FileProcessingTask.TASK_TYPE_PROCESS_CONTENT.equals(e.getEventType())).count());
    }
}
