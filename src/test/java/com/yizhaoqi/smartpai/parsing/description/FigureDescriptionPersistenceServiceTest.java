package com.yizhaoqi.smartpai.parsing.description;

import com.yizhaoqi.smartpai.client.FigureDescriptionClient;
import com.yizhaoqi.smartpai.client.FigureDescriptionProperties;
import com.yizhaoqi.smartpai.model.DocumentFigure;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.repository.DocumentFigureRepository;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SQL and Spring AOP transactions; no running application or external infrastructure. */
class FigureDescriptionPersistenceServiceTest {
    private static final String MD5 = "abc123";
    private static LocalContainerEntityManagerFactoryBean factory;
    private static JpaTransactionManager manager;
    private static TransactionTemplate transaction;
    private static FileContentRepository contents;
    private static DocumentFigureRepository figures;
    private FigureDescriptionProperties properties;
    private FigureDescriptionPersistenceService persistence;
    private Long figureId;

    @BeforeAll
    static void createDatabase() {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource(
                "jdbc:h2:mem:figure-description-persistence;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", ""));
        factory.setManagedTypes(PersistenceManagedTypes.of(FileContent.class.getName(), DocumentFigure.class.getName()));
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
            contents.deleteAllInBatch();
            FileContent content = new FileContent();
            content.setFileMd5(MD5);
            content.setObjectPath("merged/" + MD5);
            content.setTotalSize(99);
            content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
            contents.saveAndFlush(content);
            figureId = figures.saveAndFlush(figure(MD5, 1, 1, 1)).getId();
        });
        properties = new FigureDescriptionProperties();
        persistence = proxy(new FigureDescriptionPersistenceService(contents, figures, properties));
    }

    @Test
    void commitsDescriptionWithoutChangingParsedStateOrOtherMetadata() throws Exception {
        assertEquals(Optional.of("客观描述"), persistence.save(MD5, 1, figureId, "客观描述"));
        DocumentFigure row = storedFigure();
        assertEquals("客观描述", row.getDescription());
        assertEquals("原图注", row.getCaption());
        assertEquals("OCR", row.getOcrText());
        assertEquals("附近正文", row.getNearbyText());
        assertEquals("figures/abc123/1/page-1-figure-1.png", row.getImagePath());
        assertEquals(FileContent.ProcessingStatus.PARSED, contents.findByFileMd5(MD5).orElseThrow().getProcessingStatus());
    }

    @Test
    void existingDescriptionWinsAndIsNotOverwritten() throws Exception {
        persistence.save(MD5, 1, figureId, "先完成的描述");
        assertEquals(Optional.of("先完成的描述"), persistence.save(MD5, 1, figureId, "迟到的描述"));
        assertEquals("先完成的描述", storedFigure().getDescription());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n "})
    void emptyDescriptionIsRejectedWithoutWriting(String description) {
        assertThrows(IllegalArgumentException.class, () -> persistence.save(MD5, 1, figureId, description));
        assertNull(storedFigure().getDescription());
    }

    @Test
    void oversizedDescriptionIsRejectedWithoutWriting() {
        properties.setMaxDescriptionLength(4);
        assertThrows(IllegalArgumentException.class, () -> persistence.save(MD5, 1, figureId, "12345"));
        assertNull(storedFigure().getDescription());
    }

    @Test
    void trimsDescriptionBeforeIndependentCommit() throws Exception {
        assertEquals(Optional.of("简洁描述"), persistence.save(MD5, 1, figureId, " \n简洁描述\t "));
        assertEquals("简洁描述", storedFigure().getDescription());
    }

    @Test
    void generationChangePreventsStaleDescriptionWrite() throws Exception {
        transaction.executeWithoutResult(tx -> contents.findForUpdate(MD5).orElseThrow().setProcessingGeneration(2));
        assertTrue(persistence.save(MD5, 1, figureId, "旧代次描述").isEmpty());
        assertNull(storedFigure().getDescription());
    }

    @ParameterizedTest
    @EnumSource(value = FileContent.ProcessingStatus.class, names = {"MERGED", "INDEXED", "FAILED"})
    void statesOtherThanParsedPreventDescriptionWrite(FileContent.ProcessingStatus status) throws Exception {
        transaction.executeWithoutResult(tx -> contents.findForUpdate(MD5).orElseThrow().setProcessingStatus(status));
        assertTrue(persistence.save(MD5, 1, figureId, "迟到描述").isEmpty());
        assertNull(storedFigure().getDescription());
    }

    @Test
    void missingContentCannotAuthorizeFigureWrite() throws Exception {
        transaction.executeWithoutResult(tx -> contents.deleteAllInBatch());
        assertTrue(persistence.save(MD5, 1, figureId, "无内容记录").isEmpty());
        assertNull(storedFigure().getDescription());
    }

    @Test
    void figureDeletedDuringModelCallIsNotRecreated() throws Exception {
        transaction.executeWithoutResult(tx -> figures.deleteById(figureId));
        assertTrue(persistence.save(MD5, 1, figureId, "已删除图片的描述").isEmpty());
        assertTrue(figures.findById(figureId).isEmpty());
    }

    @Test
    void figureFromDifferentContentCannotBeUpdatedById() throws Exception {
        Long other = transaction.execute(tx -> figures.saveAndFlush(figure("other", 1, 1, 1)).getId());
        assertTrue(persistence.save(MD5, 1, other, "错误文件描述").isEmpty());
        assertNull(figures.findById(other).orElseThrow().getDescription());
    }

    @Test
    void figureFromDifferentGenerationCannotBeUpdatedById() throws Exception {
        Long other = transaction.execute(tx -> figures.saveAndFlush(figure(MD5, 2, 1, 1)).getId());
        assertTrue(persistence.save(MD5, 1, other, "错误代次描述").isEmpty());
        assertNull(figures.findById(other).orElseThrow().getDescription());
    }

    @Test
    void failureAfterSqlFlushRollsBackDescription() {
        DocumentFigureRepository failing = mock(DocumentFigureRepository.class, delegatesTo(figures));
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            figures.saveAndFlush(invocation.getArgument(0));
            throw new IllegalStateException("failure after SQL flush");
        }).when(failing).saveAndFlush(any());
        FigureDescriptionPersistenceService service = proxy(new FigureDescriptionPersistenceService(contents, failing, properties));
        assertThrows(IllegalStateException.class, () -> service.save(MD5, 1, figureId, "必须回滚"));
        assertNull(storedFigure().getDescription());
    }

    @Test
    void independentDescriptionTransactionSurvivesCallerRollback() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(Optional.of("独立完成"), persistence.save(MD5, 1, figureId, "独立完成"));
            throw new IllegalStateException("caller rollback");
        }));
        assertEquals("独立完成", storedFigure().getDescription());
    }

    @Test
    void concurrentShortTransactionsReuseOneWinningDescription() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<String>> first = workers.submit(() -> { start.await(); return persistence.save(MD5, 1, figureId, "描述甲"); });
            Future<Optional<String>> second = workers.submit(() -> { start.await(); return persistence.save(MD5, 1, figureId, "描述乙"); });
            start.countDown();
            Optional<String> a = first.get(15, TimeUnit.SECONDS);
            Optional<String> b = second.get(15, TimeUnit.SECONDS);
            assertTrue(a.isPresent());
            assertEquals(a, b);
            assertEquals(a.orElseThrow(), storedFigure().getDescription());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void imageAndModelCallsSuspendCallerTransactionWhileEachDescriptionCommitsIndependently() throws Exception {
        FigureImageReader images = imageReader();
        FigureDescriptionClient client = mock(FigureDescriptionClient.class);
        when(client.describe(any(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    return "独立模型结果";
                });
        FigureDescriptionService service = coordinator(images, client);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            try {
                assertTrue(service.describe(MD5, 1));
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("caller rollback after description committed");
        }));
        assertEquals("独立模型结果", storedFigure().getDescription());
    }

    @Test
    void generationChangedWhileModelRunsCannotOverwriteCurrentArtifacts() throws Exception {
        FigureDescriptionClient client = mock(FigureDescriptionClient.class);
        when(client.describe(any(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    transaction.executeWithoutResult(tx -> contents.findForUpdate(MD5).orElseThrow().setProcessingGeneration(2));
                    return "旧代次模型结果";
                });
        assertFalse(coordinator(imageReader(), client).describe(MD5, 1));
        assertNull(storedFigure().getDescription());
        assertEquals(2, contents.findByFileMd5(MD5).orElseThrow().getProcessingGeneration());
    }

    @ParameterizedTest
    @EnumSource(value = FileContent.ProcessingStatus.class, names = {"MERGED", "INDEXED", "FAILED"})
    void stateChangedWhileModelRunsCannotPersistLateResult(FileContent.ProcessingStatus status) throws Exception {
        FigureDescriptionClient client = mock(FigureDescriptionClient.class);
        when(client.describe(any(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    transaction.executeWithoutResult(tx -> contents.findForUpdate(MD5).orElseThrow().setProcessingStatus(status));
                    return "迟到模型结果";
                });
        assertFalse(coordinator(imageReader(), client).describe(MD5, 1));
        assertNull(storedFigure().getDescription());
    }

    @Test
    void partialSuccessSurvivesFailureAndRetryOnlyDescribesMissingFigure() throws Exception {
        Long secondId = transaction.execute(tx -> figures.saveAndFlush(figure(MD5, 1, 1, 2)).getId());
        FigureDescriptionClient client = mock(FigureDescriptionClient.class);
        when(client.describe(any(), anyString(), eq("原图注"), nullable(String.class), nullable(String.class)))
                .thenReturn("第一张描述")
                .thenThrow(new IOException("second model call failed"))
                .thenReturn("第二张描述");
        FigureImageReader images = imageReader();
        FigureDescriptionService service = coordinator(images, client);
        assertThrows(IOException.class, () -> service.describe(MD5, 1));
        assertEquals("第一张描述", storedFigure().getDescription());
        assertNull(figures.findById(secondId).orElseThrow().getDescription());
        assertTrue(service.describe(MD5, 1));
        assertEquals("第一张描述", storedFigure().getDescription());
        assertEquals("第二张描述", figures.findById(secondId).orElseThrow().getDescription());
        verify(client, times(3)).describe(any(), anyString(), eq("原图注"), nullable(String.class), nullable(String.class));
        verify(images, times(1)).read("figures/abc123/1/page-1-figure-1.png");
        verify(images, times(2)).read("figures/abc123/1/page-1-figure-2.png");
    }

    @Test
    void generationScopedRepositoryQuerySortsPagesAndFigureIndices() {
        transaction.executeWithoutResult(tx -> {
            figures.saveAndFlush(figure(MD5, 1, 3, 1));
            figures.saveAndFlush(figure(MD5, 1, 1, 3));
            figures.saveAndFlush(figure(MD5, 1, 2, 1));
            figures.saveAndFlush(figure(MD5, 1, 1, 2));
            figures.saveAndFlush(figure(MD5, 2, 1, 1));
            figures.saveAndFlush(figure("other", 1, 1, 1));
        });
        List<DocumentFigure> rows = figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L);
        assertEquals(List.of("1:1", "1:2", "1:3", "2:1", "3:1"),
                rows.stream().map(row -> row.getPageNumber() + ":" + row.getFigureIndex()).toList());
        assertTrue(rows.stream().allMatch(row -> MD5.equals(row.getFileMd5()) && row.getProcessingGeneration() == 1));
    }

    private FigureDescriptionService coordinator(FigureImageReader images, FigureDescriptionClient client) {
        return proxy(new FigureDescriptionService(contents, figures, images, client, persistence, properties));
    }

    private FigureImageReader imageReader() throws IOException {
        FigureImageReader images = mock(FigureImageReader.class);
        when(images.read(anyString())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new FigureImageReader.FigureImage(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, "image/png");
        });
        return images;
    }

    private DocumentFigure storedFigure() {
        return figures.findById(figureId).orElseThrow();
    }

    private static DocumentFigure figure(String md5, long generation, int page, int index) {
        DocumentFigure figure = new DocumentFigure();
        figure.setFileMd5(md5);
        figure.setProcessingGeneration(generation);
        figure.setPageNumber(page);
        figure.setFigureIndex(index);
        figure.setImagePath("figures/" + md5 + "/" + generation + "/page-" + page + "-figure-" + index + ".png");
        figure.setCaption("原图注");
        figure.setOcrText("OCR");
        figure.setNearbyText("附近正文");
        return figure;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
