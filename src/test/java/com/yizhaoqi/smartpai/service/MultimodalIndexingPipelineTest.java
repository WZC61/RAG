package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.*;
import com.yizhaoqi.smartpai.consumer.FileProcessingConsumer;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.parsing.DocumentParsingService;
import com.yizhaoqi.smartpai.parsing.description.*;
import com.yizhaoqi.smartpai.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Consumer, description orchestration, JPA transactions, vector assembly and checkpoints.
 * External MinIO/Qwen/Embedding/ES are test doubles: no paid APIs or existing documents are touched. */
class MultimodalIndexingPipelineTest {
    private static LocalContainerEntityManagerFactoryBean factory;
    private static JpaTransactionManager manager;
    private static TransactionTemplate tx;
    private static FileContentRepository contents;
    private static FileUploadRepository uploads;
    private static DocumentVectorRepository texts;
    private static DocumentFigureRepository figures;
    private FigureDescriptionClient model;
    private EmbeddingClient embedding;
    private ElasticsearchService es;
    private FileProcessingConsumer consumer;
    private FileContentProcessingService checkpoints;
    private FileContentProcessingService checkpointTarget;
    private FileProcessingTask task;
    private final Map<String, EsDocument> indexed = new LinkedHashMap<>();

    @BeforeAll
    static void database() {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:multimodal-indexing;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        factory.setManagedTypes(PersistenceManagedTypes.of(FileContent.class.getName(), FileUpload.class.getName(),
                DocumentVector.class.getName(), DocumentFigure.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        factory.afterPropertiesSet();
        manager = new JpaTransactionManager(factory.getObject()); tx = new TransactionTemplate(manager);
        var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
        repositories.addRepositoryProxyPostProcessor((proxy, info) ->
                proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())));
        contents = repositories.getRepository(FileContentRepository.class);
        uploads = repositories.getRepository(FileUploadRepository.class);
        texts = repositories.getRepository(DocumentVectorRepository.class);
        figures = repositories.getRepository(DocumentFigureRepository.class);
    }

    @AfterAll
    static void close() { if (factory != null) factory.destroy(); }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        tx.executeWithoutResult(status -> {
            figures.deleteAllInBatch(); texts.deleteAllInBatch(); uploads.deleteAllInBatch(); contents.deleteAllInBatch();
            var content = new FileContent(); content.setFileMd5("abc"); content.setObjectPath("merged/abc");
            content.setProcessingStatus(FileContent.ProcessingStatus.PARSED); contents.saveAndFlush(content);
            var upload = new FileUpload(); upload.setFileMd5("abc"); upload.setUserId("1");
            upload.setOrgTag("ORG"); upload.setStatus(FileUpload.STATUS_COMPLETED); uploads.saveAndFlush(upload);
        });
        model = mock(FigureDescriptionClient.class);
        when(model.describe(any(), anyString(), any(), any(), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return "description " + call.getArgument(2);
        });
        var images = mock(FigureImageReader.class);
        when(images.read(anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new FigureImageReader.FigureImage(new byte[]{1, 2}, "image/png");
        });
        var properties = new FigureDescriptionProperties();
        var persistence = proxy(new FigureDescriptionPersistenceService(contents, figures, properties));
        var descriptions = proxy(new FigureDescriptionService(contents, figures, images, model, persistence, properties));
        embedding = mock(EmbeddingClient.class);
        when(embedding.embedWithUsage(anyList(), eq("1"), eq(EmbeddingClient.UsageType.UPLOAD))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            List<String> inputs = call.getArgument(0);
            return new EmbeddingClient.EmbeddingUsageResult(inputs.stream().map(input -> new float[]{1, 2}).toList(), inputs.size() * 10, "model");
        });
        es = mock(ElasticsearchService.class);
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(FileContent.ProcessingStatus.PARSED, content().getProcessingStatus());
            ((List<EsDocument>) call.getArgument(0)).forEach(doc -> indexed.put(doc.getId(), doc));
            return null;
        }).when(es).bulkIndex(anyList());
        var vectorization = new VectorizationService();
        ReflectionTestUtils.setField(vectorization, "embeddingClient", embedding);
        ReflectionTestUtils.setField(vectorization, "elasticsearchService", es);
        ReflectionTestUtils.setField(vectorization, "documentVectorRepository", texts);
        ReflectionTestUtils.setField(vectorization, "documentFigureRepository", figures);
        ReflectionTestUtils.setField(vectorization, "fileContentRepository", contents);
        ReflectionTestUtils.setField(vectorization, "fileUploadRepository", uploads);
        ReflectionTestUtils.setField(vectorization, "contentIndexWriter", proxy(new ContentIndexWriter(contents, uploads, es)));
        checkpointTarget = spy(new FileContentProcessingService(contents, org.mockito.Mockito.mock(com.yizhaoqi.smartpai.repository.ProcessingOutboxRepository.class)));
        checkpoints = proxy(checkpointTarget);
        consumer = new FileProcessingConsumer(mock(ParseService.class), vectorization, mock(DocumentService.class));
        ReflectionTestUtils.setField(consumer, "contentProcessing", checkpoints);
        ReflectionTestUtils.setField(consumer, "files", uploads);
        ReflectionTestUtils.setField(consumer, "parsingService", mock(DocumentParsingService.class));
        ReflectionTestUtils.setField(consumer, "figureDescriptions", descriptions);
        task = new FileProcessingTask("abc", null, "document.pdf", null, null, false,
                FileProcessingTask.TASK_TYPE_PROCESS_CONTENT, "1");
        task.setProcessingGeneration(1L); task.setEventId("PROCESS_CONTENT:abc:1"); task.setObjectPath("merged/abc");
    }

    @ParameterizedTest
    @CsvSource({"1,2", "1,0", "0,2"})
    void textFigureAndFigureOnlyAllReachIndexedAfterEveryDocumentSucceeds(int textCount, int figureCount) throws Exception {
        seed(textCount, figureCount);
        consumer.processTask(task);
        assertEquals(textCount + figureCount, indexed.size());
        assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
        assertEquals(textCount + figureCount, content().getActualChunkCount());
        assertEquals((textCount + figureCount) * 10L, content().getActualEmbeddingTokens());
        assertNotNull(content().getIndexedAt());
        verify(model, times(figureCount)).describe(any(), anyString(), any(), any(), any());
        for (var doc : indexed.values()) {
            assertEquals(1L, doc.getProcessingGeneration());
            assertNotNull(doc.getDocumentType());
            if (doc.getDocumentType() == EsDocument.DocumentType.FIGURE) assertNotNull(doc.getImagePath());
        }
        consumer.processTask(task); // completed task duplicates do no external work
        verify(es, times(1)).bulkIndex(anyList());
    }

    @Test
    void noArtifactsStaysParsedWithoutIndexWrites() {
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.PARSED, content().getProcessingStatus());
        assertNull(content().getIndexedAt()); assertNotNull(content().getProcessingError());
        verifyNoInteractions(model, embedding, es);
    }

    @Test
    void secondDescriptionFailurePreservesFirstAndRetryReusesIt() throws Exception {
        seed(1, 2);
        when(model.describe(any(), anyString(), eq("caption 2"), any(), any()))
                .thenThrow(new IOException("Qwen unavailable")).thenReturn("description caption 2");
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.PARSED, content().getProcessingStatus());
        var rows = figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc("abc", 1L);
        assertEquals("description caption 1", rows.get(0).getDescription()); assertNull(rows.get(1).getDescription());
        verifyNoInteractions(embedding, es);
        consumer.processTask(task);
        verify(model, times(1)).describe(any(), anyString(), eq("caption 1"), any(), any());
        verify(model, times(2)).describe(any(), anyString(), eq("caption 2"), any(), any());
        assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
    }

    @Test
    void figureEmbeddingFailureRetainsDescriptionsAndRetriesBothModalities() throws Exception {
        seed(1, 2);
        AtomicBoolean failed = new AtomicBoolean();
        when(embedding.embedWithUsage(anyList(), anyString(), any())).thenAnswer(call -> {
            List<String> inputs = call.getArgument(0);
            if (inputs.size() == 2 && !failed.getAndSet(true)) throw new IOException("embedding unavailable");
            return new EmbeddingClient.EmbeddingUsageResult(inputs.stream().map(input -> new float[]{1, 2}).toList(), 10 * inputs.size(), "model");
        });
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.PARSED, content().getProcessingStatus());
        verifyNoInteractions(es);
        consumer.processTask(task);
        verify(model, times(2)).describe(any(), anyString(), any(), any(), any());
        assertEquals(3, indexed.size()); assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialEsSuccessRetriesStableIdsWithoutDuplicateDocsOrDescriptions() throws Exception {
        seed(1, 2);
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(call -> {
            List<EsDocument> batch = call.getArgument(0);
            if (!failed.getAndSet(true)) {
                indexed.put(batch.get(0).getId(), batch.get(0));
                throw new IllegalStateException("partial ES failure");
            }
            batch.forEach(doc -> indexed.put(doc.getId(), doc)); return null;
        }).when(es).bulkIndex(anyList());
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(1, indexed.size()); assertEquals(FileContent.ProcessingStatus.PARSED, content().getProcessingStatus());
        consumer.processTask(task);
        assertEquals(Set.of("TEXT:abc:1:1", "FIGURE:abc:1:1:1", "FIGURE:abc:1:1:2"), indexed.keySet());
        verify(model, times(2)).describe(any(), anyString(), any(), any(), any());
        assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
    }

    @Test
    void indexedCommitFailureLeavesParsedAndRetryOverwritesSameDocuments() throws Exception {
        seed(1, 1);
        doThrow(new IllegalStateException("DB unavailable")).doCallRealMethod().when(checkpointTarget).indexed(eq("abc"), eq(1L), any());
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.PARSED, content().getProcessingStatus()); assertEquals(2, indexed.size());
        consumer.processTask(task);
        assertEquals(2, indexed.size()); verify(model, times(1)).describe(any(), anyString(), any(), any(), any());
        assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
    }

    @Test
    void oldGenerationDuringModelCallCannotPersistOrIndex() throws Exception {
        seed(1, 1);
        when(model.describe(any(), anyString(), any(), any(), any())).thenAnswer(call -> {
            tx.executeWithoutResult(status -> { var row = contents.findForUpdate("abc").orElseThrow(); row.setProcessingGeneration(2); });
            return "old description";
        });
        consumer.processTask(task);
        assertEquals(2, content().getProcessingGeneration());
        assertEquals(FileContent.ProcessingStatus.PARSED, content().getProcessingStatus());
        assertNull(figures.findByFileMd5("abc").get(0).getDescription()); verifyNoInteractions(embedding, es);
    }

    @Test
    void indexedTransitionRejectsMergedAndZeroIndexResults() {
        assertThrows(IllegalStateException.class, () -> checkpoints.indexed("abc", 1, new VectorizationService.VectorizationUsageResult(0, 0, "model")));
        tx.executeWithoutResult(status -> contents.findForUpdate("abc").orElseThrow().setProcessingStatus(FileContent.ProcessingStatus.MERGED));
        assertThrows(IllegalStateException.class, () -> checkpoints.indexed("abc", 1, new VectorizationService.VectorizationUsageResult(10, 1, "model")));
        assertEquals(FileContent.ProcessingStatus.MERGED, content().getProcessingStatus());
    }

    private void seed(int textCount, int figureCount) {
        tx.executeWithoutResult(status -> {
            for (int i = 1; i <= textCount; i++) {
                var row = new DocumentVector(); row.setFileMd5("abc"); row.setChunkId(i); row.setTextContent("body " + i);
                row.setUserId("1"); row.setPageNumber(1); texts.save(row);
            }
            for (int i = 1; i <= figureCount; i++) {
                var row = new DocumentFigure(); row.setFileMd5("abc"); row.setProcessingGeneration(1L);
                row.setPageNumber(1); row.setFigureIndex(i); row.setCaption("caption " + i);
                row.setImagePath("figures/abc/1/page-1-figure-" + i + ".png"); row.setBbox("[1,2,3,4]"); figures.save(row);
            }
        });
    }

    private FileContent content() { return contents.findByFileMd5("abc").orElseThrow(); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target) {
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
