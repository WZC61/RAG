package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.controller.UploadController;
import com.yizhaoqi.smartpai.model.ChunkInfo;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.*;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real CAS/database state; two service instances share a controlled mock distributed lock and storage. */
class MergeConcurrencyTest {
    private static LocalContainerEntityManagerFactoryBean factory;
    private static TransactionTemplate transaction;
    private static ChunkInfoRepository chunks;
    private static FileUploadRepository files;
    private final Map<String, Long> objects = new ConcurrentHashMap<>();
    private final ReentrantLock mutex = new ReentrantLock();
    private MinioClient minio;
    private RedissonClient redissonA;
    private RedissonClient redissonB;
    private RLock lock;
    private UploadController controllerA;
    private UploadController controllerB;

    @BeforeAll
    static void createDatabase() {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:merge-global;DB_CLOSE_DELAY=-1", "sa", ""));
        factory.setManagedTypes(PersistenceManagedTypes.of(FileUpload.class.getName(), ChunkInfo.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        JpaTransactionManager manager = new JpaTransactionManager(factory.getObject());
        transaction = new TransactionTemplate(manager);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(
                SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
        repositories.addRepositoryProxyPostProcessor((proxy, information) -> {
            proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect()));
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        });
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
            for (String userId : List.of("1", "2")) {
                FileUpload file = new FileUpload();
                file.setUserId(userId);
                file.setFileMd5("md5");
                file.setFileName("test.pdf");
                file.setTotalSize(3L);
                file.setStatus(FileUpload.STATUS_UPLOADING);
                files.saveAndFlush(file);
                ChunkInfo chunk = new ChunkInfo();
                chunk.setUserId(userId);
                chunk.setFileMd5("md5");
                chunk.setChunkIndex(0);
                chunk.setChunkMd5(DigestUtils.md5Hex("abc"));
                chunk.setStoragePath(path(userId));
                chunks.saveAndFlush(chunk);
                objects.put(path(userId), 3L);
            }
        });
        minio = mock(MinioClient.class);
        when(minio.statObject(any())).thenAnswer(invocation -> {
            String path = invocation.<StatObjectArgs>getArgument(0).object();
            Long size = objects.get(path);
            if (size == null) throw missing(path);
            StatObjectResponse stat = mock(StatObjectResponse.class);
            when(stat.size()).thenReturn(size);
            return stat;
        });
        when(minio.composeObject(any())).thenAnswer(invocation -> {
            objects.put("merged/md5", 3L);
            return null;
        });
        doAnswer(invocation -> {
            objects.remove(invocation.<RemoveObjectArgs>getArgument(0).object());
            return null;
        }).when(minio).removeObject(any());
        when(minio.getPresignedObjectUrl(any())).thenReturn("https://example.com/merged/md5");
        lock = mock(RLock.class);
        when(lock.tryLock(30, TimeUnit.SECONDS)).thenAnswer(invocation -> mutex.tryLock(10, TimeUnit.SECONDS));
        when(lock.isHeldByCurrentThread()).thenAnswer(invocation -> mutex.isHeldByCurrentThread());
        doAnswer(invocation -> { mutex.unlock(); return null; }).when(lock).unlock();
        redissonA = mock(RedissonClient.class);
        redissonB = mock(RedissonClient.class);
        when(redissonA.getLock("upload:merge:md5")).thenReturn(lock);
        when(redissonB.getLock("upload:merge:md5")).thenReturn(lock);
        controllerA = controller(redissonA);
        controllerB = controller(redissonB);
    }

    @SuppressWarnings("unchecked")
    private UploadController controller(RedissonClient client) throws Exception {
        UploadService service = new UploadService();
        ReflectionTestUtils.setField(service, "fileUploadRepository", files);
        ReflectionTestUtils.setField(service, "chunkInfoRepository", chunks);
        ReflectionTestUtils.setField(service, "minioClient", minio);
        ReflectionTestUtils.setField(service, "redissonClient", client);
        UploadController controller = new UploadController(service, mock(KafkaTemplate.class));
        ReflectionTestUtils.setField(controller, "fileUploadRepository", files);
        KafkaConfig kafka = mock(KafkaConfig.class);
        when(kafka.getFileProcessingTopic()).thenReturn("file-processing-topic");
        ReflectionTestUtils.setField(controller, "kafkaConfig", kafka);
        ParseService parse = mock(ParseService.class);
        when(parse.estimateEmbeddingUsage(any())).thenThrow(new IOException("test skips downstream parsing"));
        ReflectionTestUtils.setField(controller, "parseService", parse);
        return controller;
    }

    @Test
    void singleUserComposeCompletesAndLeavesOtherUserChunksUntouched() throws Exception {
        assertEquals(200, merge(controllerA, "1").getStatusCode().value());
        assertCompleted("1");
        assertRetryable("2");
        verify(minio).composeObject(any());
        assertFalse(objects.containsKey(path("1")));
        assertTrue(objects.containsKey(path("2")));
        verify(lock).unlock();
    }

    @Test
    void existingValidObjectIsReusedWithoutLockOrCompose() throws Exception {
        objects.put("merged/md5", 3L);
        assertEquals(200, merge(controllerA, "1").getStatusCode().value());
        assertCompleted("1");
        assertRetryable("2");
        verify(minio, never()).composeObject(any());
        verifyNoInteractions(redissonA, lock);
    }

    @Test
    void twoUsersOnDifferentInstancesComposeOnceAndSecondUsesLockDoubleCheck() throws Exception {
        CountDownLatch composing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondWaiting = new CountDownLatch(1);
        AtomicBoolean firstAcquisition = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (!firstAcquisition.getAndSet(false)) secondWaiting.countDown();
            return mutex.tryLock(10, TimeUnit.SECONDS);
        }).when(lock).tryLock(30, TimeUnit.SECONDS);
        doAnswer(invocation -> {
            composing.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout");
            objects.put("merged/md5", 3L);
            return null;
        }).when(minio).composeObject(any());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map<String, Object>>> a = workers.submit(() -> merge(controllerA, "1"));
            assertTrue(composing.await(10, TimeUnit.SECONDS));
            Future<ResponseEntity<Map<String, Object>>> b = workers.submit(() -> merge(controllerB, "2"));
            assertTrue(secondWaiting.await(10, TimeUnit.SECONDS));
            assertEquals(FileUpload.STATUS_MERGING, file("2").getStatus());
            release.countDown();
            assertEquals(200, a.get(10, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(200, b.get(10, TimeUnit.SECONDS).getStatusCode().value());
            assertCompleted("1");
            assertCompleted("2");
            verify(minio, times(1)).composeObject(argThat(args -> args.sources().size() == 1
                    && args.sources().get(0).object().equals(path("1"))));
            verify(minio).removeObject(argThat(args -> args.object().equals(path("1"))));
            verify(minio).removeObject(argThat(args -> args.object().equals(path("2"))));
            verify(redissonA).getLock("upload:merge:md5");
            verify(redissonB).getLock("upload:merge:md5");
            verify(lock, times(2)).unlock();
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void composeFailurePreservesChunksRestoresUploadingAndCanRetry() throws Exception {
        doThrow(new IOException("compose failed")).when(minio).composeObject(any());
        assertEquals(503, merge(controllerA, "1").getStatusCode().value());
        assertRetryable("1");
        verify(minio, never()).removeObject(any());
        doAnswer(invocation -> { objects.put("merged/md5", 3L); return null; }).when(minio).composeObject(any());
        assertEquals(200, merge(controllerA, "1").getStatusCode().value());
        assertCompleted("1");
    }

    @Test
    void lockTimeoutPreservesChunksRestoresUploadingAndDoesNotUnlockOtherOwner() throws Exception {
        doReturn(false).when(lock).tryLock(30, TimeUnit.SECONDS);
        assertEquals(503, merge(controllerA, "1").getStatusCode().value());
        assertRetryable("1");
        verify(minio, never()).composeObject(any());
        verify(minio, never()).removeObject(any());
        verify(lock, never()).unlock();
    }

    @Test
    void redisFailurePreservesChunksAndRestoresUploading() throws Exception {
        when(redissonA.getLock(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
        assertEquals(503, merge(controllerA, "1").getStatusCode().value());
        assertRetryable("1");
        verify(minio, never()).composeObject(any());
        verify(minio, never()).removeObject(any());
    }

    @Test
    void interruptedWaitRestoresThreadInterruptAndUploading() throws Exception {
        doThrow(new InterruptedException("interrupted")).when(lock).tryLock(30, TimeUnit.SECONDS);
        try {
            assertEquals(503, merge(controllerA, "1").getStatusCode().value());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertRetryable("1");
        verify(minio, never()).removeObject(any());
        verify(lock, never()).unlock();
    }

    @Test
    void existingWrongSizeIsConflictWithoutComposeOrCleanup() throws Exception {
        objects.put("merged/md5", 4L);
        assertEquals(409, merge(controllerA, "1").getStatusCode().value());
        assertRetryable("1");
        verify(minio, never()).composeObject(any());
        verify(minio, never()).removeObject(any());
        verifyNoInteractions(redissonA);
    }

    @Test
    void wrongSizeDiscoveredInsideLockIsConflictWithoutOverwrite() throws Exception {
        doAnswer(invocation -> {
            mutex.lock();
            objects.put("merged/md5", 4L);
            return true;
        }).when(lock).tryLock(30, TimeUnit.SECONDS);
        assertEquals(409, merge(controllerA, "1").getStatusCode().value());
        assertRetryable("1");
        verify(minio, never()).composeObject(any());
        verify(minio, never()).removeObject(any());
        verify(lock).unlock();
    }

    @Test
    void wrongSizeAfterComposePreservesChunksAndRestoresUploading() throws Exception {
        doAnswer(invocation -> { objects.put("merged/md5", 2L); return null; }).when(minio).composeObject(any());
        assertEquals(409, merge(controllerA, "1").getStatusCode().value());
        assertRetryable("1");
        verify(minio, never()).removeObject(any());
        verify(lock).unlock();
    }

    @Test
    void missingFinalObjectAfterComposePreservesChunksAndRestoresUploading() throws Exception {
        doReturn(null).when(minio).composeObject(any());
        assertEquals(503, merge(controllerA, "1").getStatusCode().value());
        assertRetryable("1");
        verify(minio, never()).removeObject(any());
    }

    @Test
    void missingSourceObjectFailsBeforeLockOrComposeAndRestoresUploading() throws Exception {
        objects.remove(path("1"));
        assertEquals(400, merge(controllerA, "1").getStatusCode().value());
        assertEquals(FileUpload.STATUS_UPLOADING, file("1").getStatus());
        assertEquals(1, chunks.findChunkIndexesByUserIdAndFileMd5("1", "md5").size());
        verifyNoInteractions(redissonA);
        verify(minio, never()).composeObject(any());
        verify(minio, never()).removeObject(any());
    }

    @Test
    void nonContiguousIndexesCannotComposeEvenWhenCountMatches() throws Exception {
        transaction.executeWithoutResult(status -> {
            ChunkInfo chunk = chunks.findByUserIdAndFileMd5OrderByChunkIndexAsc("1", "md5").get(0);
            chunk.setChunkIndex(1);
            chunks.saveAndFlush(chunk);
        });
        assertEquals(400, merge(controllerA, "1").getStatusCode().value());
        assertEquals(FileUpload.STATUS_UPLOADING, file("1").getStatus());
        assertTrue(objects.containsKey(path("1")));
        verify(minio, never()).composeObject(any());
        verify(minio, never()).removeObject(any());
    }

    @Test
    void sameUserCompletedMergeIsIdempotent() throws Exception {
        assertEquals(200, merge(controllerA, "1").getStatusCode().value());
        assertEquals(200, merge(controllerA, "1").getStatusCode().value());
        assertCompleted("1");
        verify(minio, times(1)).composeObject(any());
        verify(redissonA, times(1)).getLock(anyString());
    }

    @Test
    void incompleteMetadataRestoresUploadingWithoutStorageWrites() throws Exception {
        chunks.deleteByUserIdAndFileMd5("1", "md5");
        assertEquals(400, merge(controllerA, "1").getStatusCode().value());
        assertEquals(FileUpload.STATUS_UPLOADING, file("1").getStatus());
        assertTrue(objects.containsKey(path("1")));
        verify(minio, never()).composeObject(any());
        verify(minio, never()).removeObject(any());
    }

    @Test
    void sameUserMergingRequestCannotReenterAndDoesNotResetExistingOwner() throws Exception {
        FileUpload file = file("1");
        files.updateStatusIfCurrent(file.getId(), FileUpload.STATUS_UPLOADING, FileUpload.STATUS_MERGING);
        assertEquals(409, merge(controllerB, "1").getStatusCode().value());
        assertEquals(FileUpload.STATUS_MERGING, file("1").getStatus());
        verifyNoInteractions(redissonB, minio);
    }

    @Test
    void lostLockOwnershipIsNeverUnlockedByCurrentThread() throws Exception {
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        try {
            assertEquals(200, merge(controllerA, "1").getStatusCode().value());
            assertCompleted("1");
            verify(lock, never()).unlock();
        } finally {
            if (mutex.isHeldByCurrentThread()) mutex.unlock();
        }
    }

    private ResponseEntity<Map<String, Object>> merge(UploadController controller, String userId) {
        return controller.mergeFile(new UploadController.MergeRequest("md5", "test.pdf"), userId);
    }

    private FileUpload file(String userId) {
        return files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", userId).orElseThrow();
    }

    private void assertCompleted(String userId) {
        assertEquals(FileUpload.STATUS_COMPLETED, file(userId).getStatus());
        assertNotNull(file(userId).getMergedAt());
        assertEquals(List.of(), chunks.findChunkIndexesByUserIdAndFileMd5(userId, "md5"));
        assertFalse(objects.containsKey(path(userId)));
    }

    private void assertRetryable(String userId) {
        assertEquals(FileUpload.STATUS_UPLOADING, file(userId).getStatus());
        assertEquals(List.of(0), chunks.findChunkIndexesByUserIdAndFileMd5(userId, "md5"));
        assertTrue(objects.containsKey(path(userId)));
    }

    private static String path(String userId) { return "chunks/" + userId + "/md5/0"; }

    private static ErrorResponseException missing(String path) {
        return new ErrorResponseException(new ErrorResponse("NoSuchKey", "missing", "uploads", path, null, null, null), null, "");
    }
}
