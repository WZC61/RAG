package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import io.minio.MinioClient;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real file_upload persistence and mocked storage; no application or external services start. */
class UploadInitializationTest {
    private static LocalContainerEntityManagerFactoryBean factory;
    private static TransactionTemplate transaction;
    private static FileUploadRepository repository;
    private UploadService service;
    private MinioClient minio;
    private ChunkInfoRepository chunks;


    @BeforeAll
    static void createDatabase() {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:upload-init;DB_CLOSE_DELAY=-1", "sa", ""));
        factory.setManagedTypes(PersistenceManagedTypes.of(FileUpload.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        transaction = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        repository = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()))
                .getRepository(FileUploadRepository.class);
    }

    @AfterAll
    static void closeDatabase() {
        if (factory != null) factory.destroy();
    }

    @BeforeEach
    void setUp() {
        transaction.executeWithoutResult(status -> repository.deleteAllInBatch());
        minio = mock(MinioClient.class);
        chunks = mock(ChunkInfoRepository.class);

        service = new UploadService();
        ReflectionTestUtils.setField(service, "fileUploadRepository", repository);
        ReflectionTestUtils.setField(service, "minioClient", minio);
        ReflectionTestUtils.setField(service, "chunkInfoRepository", chunks);

    }

    @Test
    void missingMergedObjectCreatesUploadingRecordAndRepeatedInitReusesIt() throws Exception {
        when(minio.statObject(any())).thenThrow(storageError("NoSuchKey"));

        FileUpload first = initialize("1", "TEAM_A", false);
        FileUpload second = initialize("1", "TEAM_A", false);

        assertEquals(FileUpload.STATUS_UPLOADING, first.getStatus());
        assertEquals(first.getId(), second.getId());
        assertEquals(1, repository.countByFileMd5AndUserId("md5", "1"));
        assertEquals("test.pdf", first.getFileName());
        assertEquals(1024L, first.getTotalSize());
        verifyNoInteractions(chunks);
        verify(minio, never()).putObject(any());
    }

    @Test
    void existingMergedObjectCompletesCurrentUserWithoutChunkOrMergeWork() throws Exception {
        mergedExists();

        FileUpload result = initialize("1", "TEAM_A", false);

        assertEquals(FileUpload.STATUS_COMPLETED, result.getStatus());
        assertNotNull(result.getMergedAt());
        assertEquals("1", result.getUserId());
        assertNull(result.getVectorizationStatus());
        assertEquals(FileUpload.STATUS_COMPLETED,
                repository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1").orElseThrow().getStatus());
        verifyNoInteractions(chunks);
        verify(minio, never()).putObject(any());
        verify(minio, never()).composeObject(any());
        verify(minio, never()).removeObject(any());
    }

    @Test
    void differentUsersReuseOnePhysicalFileButKeepSeparateMetadata() throws Exception {
        mergedExists();

        FileUpload first = initialize("1", "TEAM_A", false);
        FileUpload second = initialize("2", "TEAM_B", true);

        assertNotEquals(first.getId(), second.getId());
        assertEquals(2, repository.count());
        assertEquals(FileUpload.STATUS_COMPLETED, first.getStatus());
        assertEquals(FileUpload.STATUS_COMPLETED, second.getStatus());
        assertEquals("TEAM_A", first.getOrgTag());
        assertFalse(first.isPublic());
        assertEquals("TEAM_B", second.getOrgTag());
        assertTrue(second.isPublic());
    }

    @Test
    void repeatedInstantInitKeepsOneRecordAndExistingAsyncMetadata() throws Exception {
        mergedExists();
        FileUpload first = initialize("1", "TEAM_A", false);
        transaction.executeWithoutResult(status -> {
            FileUpload file = repository.findById(first.getId()).orElseThrow();
            file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_COMPLETED);
            file.setActualEmbeddingTokens(100L);
            repository.saveAndFlush(file);
        });
        FileUpload before = repository.findById(first.getId()).orElseThrow();

        FileUpload second = initialize("1", "TEAM_B", true);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, repository.count());
        assertEquals(before.getMergedAt(), second.getMergedAt());
        assertEquals("TEAM_A", second.getOrgTag());
        assertFalse(second.isPublic());
        assertEquals(FileUpload.VECTORIZATION_STATUS_COMPLETED, second.getVectorizationStatus());
        assertEquals(100L, second.getActualEmbeddingTokens());
    }

    @Test
    void storageFailureIsNotTreatedAsMissingFile() throws Exception {
        when(minio.statObject(any())).thenThrow(storageError("AccessDenied"));

        CustomException error = assertThrows(CustomException.class, () -> initialize("1", "TEAM_A", false));

        assertEquals(503, error.getStatus().value());
        assertEquals(0, repository.count());
    }

    @Test
    void existingObjectWithDifferentSizeIsRejected() throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(2048L);
        when(minio.statObject(any())).thenReturn(stat);

        assertEquals(409, assertThrows(CustomException.class, () -> initialize("1", "TEAM_A", false)).getStatus().value());
        assertEquals(0, repository.count());
    }

    @Test
    void initDoesNotOverrideAnActiveMerge() throws Exception {
        mergedExists();
        FileUpload file = initialize("1", "TEAM_A", false);
        transaction.executeWithoutResult(status -> repository.updateStatusIfCurrent(
                file.getId(), FileUpload.STATUS_COMPLETED, FileUpload.STATUS_MERGING));

        assertEquals(409, assertThrows(CustomException.class, () -> initialize("1", "TEAM_A", false)).getStatus().value());
        assertEquals(FileUpload.STATUS_MERGING, repository.findById(file.getId()).orElseThrow().getStatus());
    }

    @Test
    void missingObjectReopensCompletedUploadSoChunksCanBeUploaded() throws Exception {
        mergedExists();
        FileUpload file = initialize("1", "TEAM_A", false);
        when(minio.statObject(any())).thenThrow(storageError("NoSuchKey"));

        FileUpload reopened = initialize("1", "TEAM_A", false);

        assertEquals(file.getId(), reopened.getId());
        assertEquals(FileUpload.STATUS_UPLOADING, reopened.getStatus());
        assertNull(reopened.getMergedAt());
    }

    private FileUpload initialize(String userId, String orgTag, boolean isPublic) {
        return transaction.execute(status -> service.initializeUpload("md5", 1024L, "test.pdf", orgTag, isPublic, userId));
    }

    private void mergedExists() throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1024L);
        when(minio.statObject(any())).thenReturn(stat);
    }

    private ErrorResponseException storageError(String code) {
        return new ErrorResponseException(new ErrorResponse(code, "test", "uploads", "merged/md5", null, null, null), null, "test");
    }
}
