package com.yizhaoqi.smartpai.parsing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.DocumentFigure;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunk;
import com.yizhaoqi.smartpai.parsing.model.ParsedDocumentArtifacts;
import com.yizhaoqi.smartpai.parsing.model.ParsedFigureContent;
import com.yizhaoqi.smartpai.parsing.model.ParsedPageContent;
import com.yizhaoqi.smartpai.repository.DocumentFigureRepository;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import com.yizhaoqi.smartpai.service.FileContentProcessingService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real in-memory JPA/SQL/AOP transactions; no running MySQL/application or external services. */
class ParsedArtifactPersistenceServiceTest {
    private static LocalContainerEntityManagerFactoryBean factory;
    private static JpaTransactionManager manager;
    private static TransactionTemplate transaction;
    private static FileContentRepository contents;
    private static DocumentVectorRepository vectors;
    private static DocumentFigureRepository figures;
    private ParsedArtifactPersistenceService persistence;
    private Long oldVectorId;
    private Long oldFigureId;
    private final LegacyPermissionContext permissions = new LegacyPermissionContext("owner-a", "TEAM-A", true);
    private final List<TextChunk> chunks = List.of(new TextChunk(1, 1, "新正文一", "锚点一"), new TextChunk(2, 2, "新正文二", "锚点二"));

    @BeforeAll
    static void createDatabase() {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource(
                "jdbc:h2:mem:parsed-artifact-persistence;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", ""));
        factory.setManagedTypes(PersistenceManagedTypes.of(
                FileContent.class.getName(), DocumentVector.class.getName(), DocumentFigure.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        factory.afterPropertiesSet();
        manager = new JpaTransactionManager(factory.getObject());
        transaction = new TransactionTemplate(manager);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(
                SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
        repositories.addRepositoryProxyPostProcessor((proxy, information) -> {
            proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect()));
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        });
        contents = repositories.getRepository(FileContentRepository.class);
        vectors = repositories.getRepository(DocumentVectorRepository.class);
        figures = repositories.getRepository(DocumentFigureRepository.class);
    }

    @AfterAll
    static void closeDatabase() {
        if (factory != null) factory.destroy();
    }

    @BeforeEach
    void setUp() {
        transaction.executeWithoutResult(tx -> {
            figures.deleteAllInBatch();
            vectors.deleteAllInBatch();
            contents.deleteAllInBatch();
            FileContent content = new FileContent();
            content.setFileMd5("abc123");
            content.setObjectPath("merged/abc123");
            content.setTotalSize(99);
            content.setProcessingError("previous failure");
            contents.saveAndFlush(content);
            DocumentVector vector = new DocumentVector();
            vector.setFileMd5("abc123");
            vector.setChunkId(1);
            vector.setUserId("old-owner");
            vector.setTextContent("旧正文");
            oldVectorId = vectors.saveAndFlush(vector).getVectorId();
            DocumentFigure figure = figureRow("abc123", 1, 1);
            figure.setCaption("旧图注");
            figure.setDescription("旧描述");
            oldFigureId = figures.saveAndFlush(figure).getId();
        });
        persistence = proxy(new ParsedArtifactPersistenceService(contents, vectors, figures, new ObjectMapper()));
    }

    @Test
    void modernNonPdfUsesRealTikaAndSameAtomicReplacementThenRetryKeepsTheRows() throws Exception {
        var parser = tikaParser();
        var service = proxy(new com.yizhaoqi.smartpai.parsing.NonPdfDocumentParsingService(parser, persistence));
        assertTrue(service.parseAndPersist("abc123", 1, textInput(), permissions));
        var rows = vectors.findByFileMd5OrderByChunkIdAsc("abc123");
        assertFalse(rows.isEmpty());
        assertEquals(java.util.stream.IntStream.rangeClosed(1, rows.size()).boxed().toList(),
                rows.stream().map(DocumentVector::getChunkId).toList());
        assertTrue(rows.stream().allMatch(row -> row.getPageNumber() == null));
        assertEquals(0, figures.findByFileMd5("abc123").size());
        assertParsed();
        assertFalse(service.parseAndPersist("abc123", 1, textInput(), permissions));
        assertEquals(rows.stream().map(DocumentVector::getVectorId).toList(),
                vectors.findByFileMd5OrderByChunkIdAsc("abc123").stream().map(DocumentVector::getVectorId).toList());
    }

    @Test
    void nonPdfInputFailureNeverTouchesOldArtifactsOrCheckpoint() {
        var service = proxy(new com.yizhaoqi.smartpai.parsing.NonPdfDocumentParsingService(tikaParser(), persistence));
        var failedInput = new java.io.InputStream() {
            @Override public int read() throws IOException { throw new IOException("input failed during extraction"); }
        };
        assertThrows(Exception.class, () -> service.parseAndPersist("abc123", 1, failedInput, permissions));
        assertOldArtifactsAndMerged();
    }

    @Test
    void nonPdfCommitFailureRollsBackReplacementAndParsedTogether() {
        var failingVectors = mock(DocumentVectorRepository.class, delegatesTo(vectors));
        doAnswer(call -> { vectors.saveAll(call.getArgument(0)); throw new IllegalStateException("commit failed"); })
                .when(failingVectors).saveAll(anyList());
        var failingCommit = proxy(new ParsedArtifactPersistenceService(contents, failingVectors, figures, new ObjectMapper()));
        var service = proxy(new com.yizhaoqi.smartpai.parsing.NonPdfDocumentParsingService(tikaParser(), failingCommit));
        assertThrows(IllegalStateException.class, () -> service.parseAndPersist("abc123", 1, textInput(), permissions));
        assertOldArtifactsAndMerged();
    }

    @Test
    void slowOldNonPdfExtractionCannotReplaceNewGenerationArtifacts() throws Exception {
        var parser = spy(tikaParser());
        var extracted = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Object chunks = call.callRealMethod(); extracted.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS)); return chunks;
        }).when(parser).parseToChunks(any());
        var service = proxy(new com.yizhaoqi.smartpai.parsing.NonPdfDocumentParsingService(parser, persistence));
        var worker = Executors.newSingleThreadExecutor();
        try {
            var old = worker.submit(() -> service.parseAndPersist("abc123", 1, textInput(), permissions));
            assertTrue(extracted.await(10, TimeUnit.SECONDS));
            transaction.executeWithoutResult(tx -> contents.findForUpdate("abc123").orElseThrow().setProcessingGeneration(2));
            var currentChunks = List.of(new TextChunk(null, 1, "current generation body", "current generation"));
            assertTrue(persistence.persist("abc123", 2, currentChunks, List.of(), permissions));
            Long currentId = vectors.findByFileMd5("abc123").get(0).getVectorId();
            release.countDown(); assertFalse(old.get(10, TimeUnit.SECONDS));
            assertEquals(2, content().getProcessingGeneration()); assertParsed();
            assertEquals(List.of(currentId), vectors.findByFileMd5("abc123").stream().map(DocumentVector::getVectorId).toList());
            assertEquals("current generation body", vectors.findByFileMd5("abc123").get(0).getTextContent());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    private com.yizhaoqi.smartpai.service.ParseService tikaParser() {
        var parser = new com.yizhaoqi.smartpai.service.ParseService();
        org.springframework.test.util.ReflectionTestUtils.setField(parser, "textChunker",
                new com.yizhaoqi.smartpai.parsing.chunk.TextChunker(32, 4, 1));
        org.springframework.test.util.ReflectionTestUtils.setField(parser, "bufferSize", 8192);
        org.springframework.test.util.ReflectionTestUtils.setField(parser, "parentChunkSize", 1048576);
        org.springframework.test.util.ReflectionTestUtils.setField(parser, "maxMemoryThreshold", 1.0);
        // Deliberately no repository injected: modern extraction must never use one.
        return parser;
    }

    private java.io.InputStream textInput() {
        return new java.io.ByteArrayInputStream("First paragraph. Another sentence.\n\nSecond paragraph with useful text."
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void commitsCompleteReplacementAndParsedCheckpointTogether() throws Exception {
        assertTrue(persistence.persist("abc123", 1, chunks, List.of(prepared(1, 1)), permissions));
        List<DocumentVector> rows = vectors.findByFileMd5OrderByChunkIdAsc("abc123");
        assertEquals(2, rows.size());
        assertEquals(List.of(1, 2), rows.stream().map(DocumentVector::getChunkId).toList());
        assertEquals(List.of(1, 2), rows.stream().map(DocumentVector::getPageNumber).toList());
        assertEquals(List.of("新正文一", "新正文二"), rows.stream().map(DocumentVector::getTextContent).toList());
        assertEquals(List.of("锚点一", "锚点二"), rows.stream().map(DocumentVector::getAnchorText).toList());
        for (DocumentVector row : rows) {
            assertEquals("abc123", row.getFileMd5());
            assertEquals("owner-a", row.getUserId());
            assertEquals("TEAM-A", row.getOrgTag());
            assertTrue(row.isPublic());
            assertNull(row.getModelVersion());
            assertNotEquals(oldVectorId, row.getVectorId());
        }
        DocumentFigure figure = figures.findByFileMd5("abc123").get(0);
        assertNotEquals(oldFigureId, figure.getId());
        assertEquals("abc123", figure.getFileMd5());
        assertEquals(1L, figure.getProcessingGeneration());
        assertEquals(1, figure.getPageNumber());
        assertEquals(1, figure.getFigureIndex());
        assertEquals("Figure 1", figure.getFigureLabel());
        assertEquals("figures/abc123/1/page-1-figure-1.png", figure.getImagePath());
        assertEquals("[0,1,2,3]", figure.getBbox());
        assertEquals("新图注", figure.getCaption());
        assertEquals("OCR", figure.getOcrText());
        assertEquals("附近正文", figure.getNearbyText());
        assertNull(figure.getDescription());
        assertNotNull(figure.getCreatedAt());
        assertNotNull(figure.getUpdatedAt());
        assertParsed();
    }

    @Test
    void figureInsertFailureRollsBackDeletionsAndAlreadyInsertedText() {
        DocumentFigureRepository failing = mock(DocumentFigureRepository.class, delegatesTo(figures));
        doAnswer(invocation -> {
            List<DocumentFigure> rows = invocation.getArgument(0);
            rows.get(0).setImagePath(null); // Real JPA NOT NULL failure after the text inserts.
            return figures.saveAll(rows);
        }).when(failing).saveAll(anyList());
        ParsedArtifactPersistenceService service = proxy(new ParsedArtifactPersistenceService(contents, vectors, failing, new ObjectMapper()));
        assertThrows(Exception.class, () -> service.persist("abc123", 1, chunks, List.of(prepared(1, 1)), permissions));
        assertOldArtifactsAndMerged();
    }

    @Test
    void failureAfterFlushingCheckpointRollsBackEveryTable() {
        FileContentRepository failing = mock(FileContentRepository.class, delegatesTo(contents));
        doAnswer(invocation -> {
            contents.saveAndFlush(invocation.getArgument(0));
            throw new IllegalStateException("failure after SQL flush");
        }).when(failing).saveAndFlush(any());
        ParsedArtifactPersistenceService service = proxy(new ParsedArtifactPersistenceService(failing, vectors, figures, new ObjectMapper()));
        assertThrows(IllegalStateException.class, () -> service.persist("abc123", 1, chunks, List.of(prepared(1, 1)), permissions));
        assertOldArtifactsAndMerged();
    }

    @Test
    void sameGenerationRetryDoesNotReplaceSuccessfulArtifacts() throws Exception {
        persistence.persist("abc123", 1, chunks, List.of(prepared(1, 1)), permissions);
        Long textId = vectors.findByFileMd5OrderByChunkIdAsc("abc123").get(0).getVectorId();
        Long figureId = figures.findByFileMd5("abc123").get(0).getId();
        assertFalse(persistence.persist("abc123", 1, List.of(new TextChunk(1, 1, "迟到正文", "迟到")), List.of(), permissions));
        assertEquals(2, vectors.countByFileMd5("abc123"));
        assertEquals(textId, vectors.findByFileMd5OrderByChunkIdAsc("abc123").get(0).getVectorId());
        assertEquals(figureId, figures.findByFileMd5("abc123").get(0).getId());
        assertParsed();
    }

    @Test
    void staleGenerationCannotReplaceNewGenerationArtifacts() throws Exception {
        transaction.executeWithoutResult(tx -> contents.findForUpdate("abc123").orElseThrow().setProcessingGeneration(2));
        assertFalse(persistence.persist("abc123", 1, chunks, List.of(prepared(1, 1)), permissions));
        assertOldArtifactsAndMerged();
        assertEquals(2, content().getProcessingGeneration());
    }

    @ParameterizedTest
    @EnumSource(value = FileContent.ProcessingStatus.class, names = {"PARSED", "INDEXED", "FAILED"})
    void nonMergedStateCannotBeOverwritten(FileContent.ProcessingStatus status) throws Exception {
        transaction.executeWithoutResult(tx -> contents.findForUpdate("abc123").orElseThrow().setProcessingStatus(status));
        assertFalse(persistence.persist("abc123", 1, chunks, List.of(prepared(1, 1)), permissions));
        assertEquals(oldVectorId, vectors.findByFileMd5("abc123").get(0).getVectorId());
        assertEquals(oldFigureId, figures.findByFileMd5("abc123").get(0).getId());
        assertEquals(status, content().getProcessingStatus());
        assertEquals("previous failure", content().getProcessingError());
    }

    @Test
    void textOnlyDocumentRemovesOldFiguresAndCommits() throws Exception {
        assertTrue(persistence.persist("abc123", 1, chunks, List.of(), permissions));
        assertTrue(figures.findByFileMd5("abc123").isEmpty());
        assertEquals(2, vectors.countByFileMd5("abc123"));
        assertParsed();
    }

    @Test
    void figureOnlyDocumentRemovesOldTextAndCommits() throws Exception {
        assertTrue(persistence.persist("abc123", 1, List.of(), List.of(prepared(1, 1)), permissions));
        assertEquals(0, vectors.countByFileMd5("abc123"));
        assertEquals(1, figures.findByFileMd5("abc123").size());
        assertParsed();
    }

    @Test
    void emptyArtifactSetNeverDeletesOldData() {
        assertThrows(IllegalArgumentException.class, () -> persistence.persist("abc123", 1, List.of(), List.of(), permissions));
        assertOldArtifactsAndMerged();
    }

    @Test
    void discontinuousChunkIndicesNeverDeleteOldData() {
        assertThrows(IllegalArgumentException.class, () -> persistence.persist("abc123", 1,
                List.of(new TextChunk(1, 2, "正文", "正文")), List.of(), permissions));
        assertOldArtifactsAndMerged();
    }

    @Test
    void externalImageUrlCannotBePersistedAsStablePath() {
        StoredFigureArtifact bad = new StoredFigureArtifact(1, 1, "Figure", "https://temporary.invalid/signed", List.of(), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> persistence.persist("abc123", 1, chunks, List.of(bad), permissions));
        assertOldArtifactsAndMerged();
    }

    @Test
    void databaseUniqueKeyRejectsDuplicateFigureIdentityAndRollsBack() {
        assertThrows(Exception.class, () -> persistence.persist("abc123", 1, chunks,
                List.of(prepared(1, 1), prepared(1, 1)), permissions));
        assertOldArtifactsAndMerged();
    }

    @Test
    void replacementLeavesOtherContentUntouched() throws Exception {
        transaction.executeWithoutResult(tx -> figures.saveAndFlush(figureRow("other", 1, 1)));
        persistence.persist("abc123", 1, chunks, List.of(prepared(1, 1)), permissions);
        assertEquals(1, figures.findByFileMd5("other").size());
        assertEquals("other", figures.findByFileMd5("other").get(0).getFileMd5());
    }

    @Test
    void coordinatorSuspendsOuterTransactionDuringImagesAndCommitsIndependently() throws Exception {
        FigureImageStorageService images = mock(FigureImageStorageService.class);
        when(images.store(anyString(), anyLong(), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return "figures/abc123/1/page-1-figure-1.png";
        });
        DocumentParsingPersistenceCoordinator coordinator = proxy(new DocumentParsingPersistenceCoordinator(
                new FileContentProcessingService(contents, org.mockito.Mockito.mock(com.yizhaoqi.smartpai.repository.ProcessingOutboxRepository.class)), images, persistence));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            try {
                assertTrue(coordinator.persist("abc123", 1, artifacts(), chunks, permissions));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("outer rollback after independent parse commit");
        }));
        assertParsed();
        assertEquals(2, vectors.countByFileMd5("abc123"));
    }

    @Test
    void secondImageFailureLeavesRealDatabaseCompletelyUnchanged() throws Exception {
        FigureImageStorageService images = mock(FigureImageStorageService.class);
        when(images.store(anyString(), anyLong(), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            ParsedFigureContent figure = invocation.getArgument(2);
            if (figure.figureIndex() == 2) throw new IOException("second image failed");
            return "figures/abc123/1/page-1-figure-1.png";
        });
        DocumentParsingPersistenceCoordinator coordinator = proxy(new DocumentParsingPersistenceCoordinator(
                new FileContentProcessingService(contents, org.mockito.Mockito.mock(com.yizhaoqi.smartpai.repository.ProcessingOutboxRepository.class)), images, persistence));
        ParsedDocumentArtifacts artifacts = new ParsedDocumentArtifacts(List.of(new ParsedPageContent(1, "正文")),
                List.of(parsedFigure(1), parsedFigure(2)));
        assertThrows(IOException.class, () -> coordinator.persist("abc123", 1, artifacts, chunks, permissions));
        assertOldArtifactsAndMerged();
    }

    @Test
    void newGenerationCommitsItsOwnFigureDirectory() throws Exception {
        transaction.executeWithoutResult(tx -> contents.findForUpdate("abc123").orElseThrow().setProcessingGeneration(2));
        StoredFigureArtifact next = new StoredFigureArtifact(1, 1, "Figure", "figures/abc123/2/page-1-figure-1.png", List.of(), null, null, null);
        assertTrue(persistence.persist("abc123", 2, chunks, List.of(next), permissions));
        assertEquals(2, figures.findByFileMd5("abc123").get(0).getProcessingGeneration());
        assertTrue(figures.findByFileMd5("abc123").get(0).getImagePath().contains("/2/"));
        assertParsed();
    }

    @Test
    void simultaneousCommitsHaveOnlyOneCompleteReplacement() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> commit = () -> { start.await(); return persistence.persist("abc123", 1, chunks, List.of(prepared(1, 1)), permissions); };
            Future<Boolean> first = workers.submit(commit);
            Future<Boolean> second = workers.submit(commit);
            start.countDown();
            boolean a = first.get(15, TimeUnit.SECONDS);
            boolean b = second.get(15, TimeUnit.SECONDS);
            assertTrue(a ^ b);
            assertEquals(2, vectors.countByFileMd5("abc123"));
            assertEquals(1, figures.findByFileMd5("abc123").size());
            assertParsed();
        } finally {
            workers.shutdownNow();
        }
    }

    private void assertParsed() {
        assertEquals(FileContent.ProcessingStatus.PARSED, content().getProcessingStatus());
        assertNull(content().getProcessingError());
    }

    private void assertOldArtifactsAndMerged() {
        assertEquals(1, vectors.countByFileMd5("abc123"));
        assertEquals(oldVectorId, vectors.findByFileMd5("abc123").get(0).getVectorId());
        assertEquals("旧正文", vectors.findByFileMd5("abc123").get(0).getTextContent());
        assertEquals(1, figures.findByFileMd5("abc123").size());
        assertEquals(oldFigureId, figures.findByFileMd5("abc123").get(0).getId());
        assertEquals("旧图注", figures.findByFileMd5("abc123").get(0).getCaption());
        assertEquals(FileContent.ProcessingStatus.MERGED, content().getProcessingStatus());
        assertEquals("previous failure", content().getProcessingError());
    }

    private static FileContent content() {
        return contents.findByFileMd5("abc123").orElseThrow();
    }

    private static StoredFigureArtifact prepared(int page, int index) {
        return new StoredFigureArtifact(page, index, "Figure " + index,
                "figures/abc123/1/page-" + page + "-figure-" + index + ".png",
                List.of(0, 1, 2, 3), "新图注", "OCR", "附近正文");
    }

    private static DocumentFigure figureRow(String md5, int page, int index) {
        DocumentFigure row = new DocumentFigure();
        row.setFileMd5(md5);
        row.setProcessingGeneration(1L);
        row.setPageNumber(page);
        row.setFigureIndex(index);
        row.setImagePath("figures/" + md5 + "/1/page-" + page + "-figure-" + index + ".png");
        return row;
    }

    private static ParsedDocumentArtifacts artifacts() {
        return new ParsedDocumentArtifacts(List.of(new ParsedPageContent(1, "正文")), List.of(parsedFigure(1)));
    }

    private static ParsedFigureContent parsedFigure(int index) {
        return new ParsedFigureContent(1, index, null, List.of(), null, null, null, "key", "https://images.invalid/" + index);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
