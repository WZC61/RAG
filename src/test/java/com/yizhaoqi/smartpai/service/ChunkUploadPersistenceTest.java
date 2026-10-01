package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.ChunkInfo;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.*;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real database transactions and unique constraints, with storage mocked in memory. */
class ChunkUploadPersistenceTest {
    private static LocalContainerEntityManagerFactoryBean factory;
    private static JpaTransactionManager manager;
    private static TransactionTemplate transaction;
    private static ChunkInfoRepository chunks;
    private static FileUploadRepository files;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private MinioClient minio;
    private UploadService service;

    @BeforeAll
    static void createDatabase() {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource(
                "jdbc:h2:mem:chunk-upload;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", ""));
        factory.setManagedTypes(PersistenceManagedTypes.of(FileUpload.class.getName(), ChunkInfo.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        manager = new JpaTransactionManager(factory.getObject());
        transaction = new TransactionTemplate(manager);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(
                SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
        repositories.addRepositoryProxyPostProcessor((proxy, information) -> proxy.addAdvice(
                new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect())));
        chunks = repositories.getRepository(ChunkInfoRepository.class);
        files = repositories.getRepository(FileUploadRepository.class);
    }

    @AfterAll
    static void closeDatabase() {
        if (factory != null) factory.destroy();
    }

    @BeforeEach
    void setUp() throws Exception {
        transaction.executeWithoutResult(status -> {
            chunks.deleteAllInBatch();
            files.deleteAllInBatch();
            FileUpload file = new FileUpload();
            file.setUserId("1");
            file.setFileMd5("md5");
            file.setFileName("test.pdf");
            file.setTotalSize(1024L);
            file.setStatus(FileUpload.STATUS_UPLOADING);
            files.saveAndFlush(file);
        });
        minio = mock(MinioClient.class);
        when(minio.statObject(any())).thenAnswer(invocation -> {
            String path = invocation.<StatObjectArgs>getArgument(0).object();
            if (!objects.containsKey(path)) {
                throw new ErrorResponseException(
                        new ErrorResponse("NoSuchKey", "missing", "uploads", path, "resource", "request", "host"), null, "");
            }
            return mock(StatObjectResponse.class);
        });
        when(minio.putObject(any())).thenAnswer(invocation -> {
            PutObjectArgs args = invocation.getArgument(0);
            objects.put(args.object(), args.stream().readAllBytes());
            return null;
        });
        service = new UploadService();
        ReflectionTestUtils.setField(service, "fileUploadRepository", files);
        ReflectionTestUtils.setField(service, "chunkInfoRepository", chunks);
        ReflectionTestUtils.setField(service, "minioClient", minio);
        ReflectionTestUtils.setField(service, "transactionManager", manager);
    }

    @Test
    void failedStorageRollsBackReservedRowAndRetrySucceeds() throws Exception {
        doThrow(new IOException("storage unavailable")).when(minio).putObject(any());
        assertThrows(CustomException.class, () -> upload("abc"));
        assertEquals(0, chunks.count());
        assertEquals(List.of(), service.getUploadedChunks("md5", "1"));

        doAnswer(invocation -> {
            PutObjectArgs args = invocation.getArgument(0);
            objects.put(args.object(), args.stream().readAllBytes());
            return null;
        }).when(minio).putObject(any());
        upload("abc");
        assertEquals(List.of(0), service.getUploadedChunks("md5", "1"));
        assertEquals(1, chunks.count());
    }

    @Test
    void orphanObjectIsRewrittenAndReceivesCommittedMetadata() throws Exception {
        objects.put("chunks/1/md5/0", bytes("orphan"));
        upload("abc");
        assertArrayEquals(bytes("abc"), objects.get("chunks/1/md5/0"));
        assertEquals(1, chunks.count());
        assertEquals(DigestUtils.md5Hex("abc"), chunks.findAll().get(0).getChunkMd5());
    }

    @Test
    void missingObjectRepairsExistingRowWithinOneTransaction() throws Exception {
        transaction.executeWithoutResult(status -> {
            ChunkInfo chunk = new ChunkInfo();
            chunk.setUserId("1");
            chunk.setFileMd5("md5");
            chunk.setChunkIndex(0);
            chunk.setChunkMd5(DigestUtils.md5Hex("abc"));
            chunk.setStoragePath("chunks/1/md5/0");
            chunks.saveAndFlush(chunk);
        });
        upload("abc");
        assertEquals(1, chunks.count());
        assertArrayEquals(bytes("abc"), objects.get("chunks/1/md5/0"));
    }

    @Test
    void concurrentSameContentWritesOnceAndStatusCannotSeeUncommittedReservation() throws Exception {
        concurrentUpload("abc", false);
    }

    @Test
    void concurrentDifferentContentCannotOverwriteUniqueKeyWinner() throws Exception {
        concurrentUpload("different", true);
    }

    private void concurrentUpload(String secondContent, boolean conflict) throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondReadEmpty = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        ChunkInfoRepository observing = mock(ChunkInfoRepository.class, org.mockito.AdditionalAnswers.delegatesTo(chunks));
        doAnswer(invocation -> {
            Optional<ChunkInfo> result = chunks.findByUserIdAndFileMd5AndChunkIndex("1", "md5", 0);
            if (reads.incrementAndGet() == 2 && result.isEmpty()) secondReadEmpty.countDown();
            return result;
        }).when(observing).findByUserIdAndFileMd5AndChunkIndex("1", "md5", 0);
        ReflectionTestUtils.setField(service, "chunkInfoRepository", observing);
        doAnswer(invocation -> {
            writing.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout");
            PutObjectArgs args = invocation.getArgument(0);
            objects.put(args.object(), args.stream().readAllBytes());
            return null;
        }).when(minio).putObject(any());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> { upload("abc"); return null; });
            assertTrue(writing.await(10, TimeUnit.SECONDS));
            assertEquals(List.of(), service.getUploadedChunks("md5", "1"));
            Future<?> second = workers.submit(() -> { upload(secondContent); return null; });
            assertTrue(secondReadEmpty.await(10, TimeUnit.SECONDS));
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            if (conflict) {
                ExecutionException failure = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
                assertInstanceOf(CustomException.class, failure.getCause());
                assertEquals(409, ((CustomException) failure.getCause()).getStatus().value());
            } else {
                second.get(10, TimeUnit.SECONDS);
            }
            assertEquals(1, chunks.count());
            assertEquals(List.of(0), service.getUploadedChunks("md5", "1"));
            assertArrayEquals(bytes("abc"), objects.get("chunks/1/md5/0"));
            verify(minio, times(1)).putObject(any());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private void upload(String content) throws IOException {
        service.uploadChunk("md5", 0, 1024L, "test.pdf",
                new MockMultipartFile("file", "test.pdf", "application/pdf", bytes(content)),
                "TEAM_A", false, "1", DigestUtils.md5Hex(content));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
