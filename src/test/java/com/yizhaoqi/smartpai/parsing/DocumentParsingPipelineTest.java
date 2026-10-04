package com.yizhaoqi.smartpai.parsing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.consumer.FileProcessingConsumer;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.parsing.chunk.ParsedDocumentChunker;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunker;
import com.yizhaoqi.smartpai.parsing.model.*;
import com.yizhaoqi.smartpai.parsing.persistence.*;
import com.yizhaoqi.smartpai.repository.*;
import com.yizhaoqi.smartpai.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Consumer -> assembly -> chunking -> coordinator -> JPA checkpoint; PP/images/embedding are fake. */
class DocumentParsingPipelineTest {
    private static LocalContainerEntityManagerFactoryBean factory;
    private static JpaTransactionManager manager;
    private static TransactionTemplate transaction;
    private static FileContentRepository contents;
    private static FileUploadRepository files;
    private static DocumentVectorRepository vectors;
    private static DocumentFigureRepository figures;
    @TempDir Path directory;
    private FileProcessingConsumer consumer;
    private FileProcessingTask task;
    private FigureImageStorageService images;
    private VectorizationService vectorization;
    private ParseService legacy;
    private Function<Path, DocumentParseResult> pp;
    private Path submitted;
    private int calls;
    private final VectorizationService.VectorizationUsageResult usage =
            new VectorizationService.VectorizationUsageResult(42, 2, "mock-model");

    @BeforeAll
    static void database() {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:pdf-main-pipeline;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        factory.setManagedTypes(PersistenceManagedTypes.of(FileContent.class.getName(), FileUpload.class.getName(),
                DocumentVector.class.getName(), DocumentFigure.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        factory.afterPropertiesSet();
        manager = new JpaTransactionManager(factory.getObject());
        transaction = new TransactionTemplate(manager);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
        repositories.addRepositoryProxyPostProcessor((proxy, info) -> {
            proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect()));
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        });
        contents = repositories.getRepository(FileContentRepository.class);
        files = repositories.getRepository(FileUploadRepository.class);
        vectors = repositories.getRepository(DocumentVectorRepository.class);
        figures = repositories.getRepository(DocumentFigureRepository.class);
    }

    @AfterAll
    static void closeDatabase() { if (factory != null) factory.destroy(); }

    @BeforeEach
    void setUp() throws Exception {
        transaction.executeWithoutResult(tx -> {
            figures.deleteAllInBatch(); vectors.deleteAllInBatch(); contents.deleteAllInBatch(); files.deleteAllInBatch();
            FileContent content = new FileContent();
            content.setFileMd5("md5"); content.setObjectPath("merged/md5"); content.setTotalSize(12);
            contents.saveAndFlush(content);
            FileUpload file = new FileUpload();
            file.setFileMd5("md5"); file.setUserId("1"); file.setOrgTag("TEAM_A"); file.setPublic(true);
            file.setFileName("document.pdf"); file.setTotalSize(12); file.setStatus(FileUpload.STATUS_COMPLETED);
            files.saveAndFlush(file);
        });
        Path source = directory.resolve("source.bin");
        Files.writeString(source, "%PDF-1.7\nfake");
        task = new FileProcessingTask("md5", source.toString(), "document.txt", null, null, false,
                FileProcessingTask.TASK_TYPE_PROCESS_CONTENT, "1");
        task.setObjectPath("merged/md5"); task.setProcessingGeneration(1L); task.setEventId("PROCESS_CONTENT:md5:1");
        images = mock(FigureImageStorageService.class);
        when(images.store(anyString(), anyLong(), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return "figures/md5/1/page-1-figure-1.png";
        });
        vectorization = mock(VectorizationService.class);
        when(vectorization.vectorizeWithUsage("md5", "1", "TEAM_A", true, "1")).thenReturn(usage);
        legacy = mock(ParseService.class);
        pp = path -> result();
        calls = 0;
        pipeline(figures);
    }

    private void pipeline(DocumentFigureRepository figureRows) {
        FileContentProcessingService checkpoint = proxy(new FileContentProcessingService(contents));
        ParsedArtifactPersistenceService persistence = proxy(new ParsedArtifactPersistenceService(contents, vectors, figureRows, new ObjectMapper()));
        DocumentParsingPersistenceCoordinator coordinator = proxy(new DocumentParsingPersistenceCoordinator(checkpoint, images, persistence));
        DocumentParsingService parsing = proxy(new DocumentParsingService(() -> path -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            submitted = path;
            calls++;
            return pp.apply(path);
        }, new ParsedDocumentChunker(new TextChunker(16, 0, 0)), coordinator));
        consumer = new FileProcessingConsumer(legacy, vectorization, mock(DocumentService.class));
        ReflectionTestUtils.setField(consumer, "contentProcessing", checkpoint);
        ReflectionTestUtils.setField(consumer, "files", files);
        ReflectionTestUtils.setField(consumer, "parsingService", parsing);
    }

    @Test
    void pdfCommitsAllArtifactsBeforeExistingVectorizationAndReachesIndexed() {
        when(vectorization.vectorizeWithUsage("md5", "1", "TEAM_A", true, "1")).thenAnswer(invocation -> {
            assertEquals(FileContent.ProcessingStatus.PARSED, content().getProcessingStatus());
            assertEquals(2, vectors.countByFileMd5("md5"));
            DocumentFigure figure = figures.findByFileMd5("md5").get(0);
            assertEquals("figures/md5/1/page-1-figure-1.png", figure.getImagePath());
            assertNull(figure.getDescription());
            return usage;
        });
        consumer.processTask(task);
        assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
        assertEquals(42L, content().getActualEmbeddingTokens());
        assertNull(content().getProcessingError());
        assertEquals(List.of(1, 2), vectors.findByFileMd5OrderByChunkIdAsc("md5").stream().map(DocumentVector::getPageNumber).toList());
        assertFalse(Files.exists(submitted));
        verifyNoInteractions(legacy);
    }

    @Test
    void imageFailureLeavesMergedWithoutAnyDatabaseArtifacts() throws Exception {
        doThrow(new IOException("image download failed")).when(images).store(anyString(), anyLong(), any());
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.MERGED, content().getProcessingStatus());
        assertEquals("image download failed", content().getProcessingError());
        assertEquals(0, vectors.count());
        assertEquals(0, figures.count());
        assertFalse(Files.exists(submitted));
        verifyNoInteractions(vectorization, legacy);
    }

    @Test
    void figureDatabaseFailureRollsBackTextAndCheckpointThenConsumerRecordsError() {
        DocumentFigureRepository failing = mock(DocumentFigureRepository.class, delegatesTo(figures));
        doAnswer(invocation -> {
            List<DocumentFigure> rows = invocation.getArgument(0);
            rows.get(0).setImagePath(null);
            return figures.saveAll(rows);
        }).when(failing).saveAll(anyList());
        pipeline(failing);
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.MERGED, content().getProcessingStatus());
        assertNotNull(content().getProcessingError());
        assertEquals(0, vectors.count());
        assertEquals(0, figures.count());
        assertFalse(Files.exists(submitted));
        verifyNoInteractions(vectorization, legacy);
    }

    @Test
    void vectorizationRetryReusesParsedRowsWithoutAnotherPpCallOrDownload() {
        when(vectorization.vectorizeWithUsage("md5", "1", "TEAM_A", true, "1"))
                .thenThrow(new IllegalStateException("vectorization failed")).thenReturn(usage);
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.PARSED, content().getProcessingStatus());
        Long figureId = figures.findByFileMd5("md5").get(0).getId();
        task.setFilePath(null);
        consumer.processTask(task);
        assertEquals(1, calls);
        assertEquals(figureId, figures.findByFileMd5("md5").get(0).getId());
        assertEquals(FileContent.ProcessingStatus.INDEXED, content().getProcessingStatus());
        assertFalse(Files.exists(submitted));
    }

    @Test
    void generationChangedDuringPpStopsOldTaskBeforeImagesDatabaseAndVectorization() {
        pp = path -> {
            transaction.executeWithoutResult(tx -> contents.findForUpdate("md5").orElseThrow().setProcessingGeneration(2));
            return result();
        };
        consumer.processTask(task);
        assertEquals(2, content().getProcessingGeneration());
        assertEquals(FileContent.ProcessingStatus.MERGED, content().getProcessingStatus());
        assertNull(content().getProcessingError());
        assertEquals(0, figures.count());
        assertEquals(0, vectors.count());
        assertFalse(Files.exists(submitted));
        verifyNoInteractions(images, vectorization, legacy);
    }

    private static FileContent content() { return contents.findByFileMd5("md5").orElseThrow(); }

    @Test
    void validTextAlongsideInvalidPageNeverCommitsParsedArtifacts() {
        ObjectMapper json = new ObjectMapper();
        pp = path -> {
            var pages = json.createArrayNode();
            pages.addObject().putObject("prunedResult").putArray("parsing_res_list")
                    .addObject().put("block_label", "text").put("block_content", "有效正文");
            pages.addObject(); // Malformed page, not a structurally valid blank page.
            return new PpStructureResultMapper(json).map(pages);
        };
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertEquals(FileContent.ProcessingStatus.MERGED, content().getProcessingStatus());
        assertEquals(0, vectors.count()); assertEquals(0, figures.count());
        assertFalse(Files.exists(submitted));
        verifyNoInteractions(images, vectorization, legacy);
    }

    private static DocumentParseResult result() {
        return new DocumentParseResult(List.of(new PageParseResult(1,
                List.of(new ParseBlock("text", "第一页正文", List.of(), 1, 1)),
                List.of(new FigureParseResult(1, 1, "Figure 1", List.of(), "图注", null, null, "key", "https://images.invalid/figure"))),
                new PageParseResult(2, List.of(new ParseBlock("text", "第二页正文", List.of(), 2, 2)), List.of())));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
