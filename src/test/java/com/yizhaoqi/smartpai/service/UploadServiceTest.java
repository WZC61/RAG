package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.MinioConfig;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadServiceTest {
    private final MinioClient minio = mock(MinioClient.class);
    private final FileUploadRepository files = mock(FileUploadRepository.class);
    private final ChunkInfoRepository chunks = mock(ChunkInfoRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private UploadService service;
    private final String md5 = DigestUtils.md5Hex("demo");

    @BeforeEach
    void setUp() {
        service = new UploadService();
        ReflectionTestUtils.setField(service, "minioClient", minio);
        ReflectionTestUtils.setField(service, "fileUploadRepository", files);
        ReflectionTestUtils.setField(service, "chunkInfoRepository", chunks);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        ReflectionTestUtils.setField(service, "minioConfig", mock(MinioConfig.class));
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        for (String user : List.of("1", "2")) {
            FileUpload file = new FileUpload();
            file.setId(Long.valueOf(user)); file.setUserId(user); file.setFileMd5("md5");
            file.setTotalSize(1024); file.setStatus(FileUpload.STATUS_UPLOADING);
            when(files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", user)).thenReturn(Optional.of(file));
        }
    }

    @Test
    void validMd5ReservesMetadataThenWritesVerifiedObject() throws Exception {
        upload("1", "demo", md5);
        ArgumentCaptor<ChunkInfo> row = ArgumentCaptor.forClass(ChunkInfo.class);
        var order = inOrder(chunks, minio, transactions);
        order.verify(chunks).saveAndFlush(row.capture());
        order.verify(minio).putObject(any()); order.verify(transactions).commit(any());
        assertEquals(md5, row.getValue().getChunkMd5());
        assertEquals("chunks/1/md5/0", row.getValue().getStoragePath());
    }

    @Test
    void incorrectMd5WritesNeitherStorageNorDatabase() {
        var error = assertThrows(CustomException.class, () -> upload("1", "changed", md5));
        assertEquals(400, error.getStatus().value()); assertTrue(error.getMessage().contains("MD5"));
        verifyNoInteractions(minio, chunks, files, transactions);
    }

    @Test
    void invalidMd5IsRejectedBeforeWrites() {
        assertEquals(400, assertThrows(CustomException.class, () -> upload("1", "demo", "invalid")).getStatus().value());
        verifyNoInteractions(minio, chunks, files, transactions);
    }

    @Test
    void repeatedMatchingChunkWritesObjectAndRowOnlyOnce() throws Exception {
        when(chunks.findByUserIdAndFileMd5AndChunkIndex("1", "md5", 0))
                .thenReturn(Optional.empty(), Optional.of(row("1", md5)));
        upload("1", "demo", md5); upload("1", "demo", md5.toUpperCase());
        verify(minio, times(1)).putObject(any()); verify(chunks, times(1)).saveAndFlush(any());
        verify(chunks, never()).delete(any());
    }

    @Test
    void idempotencyChecksRecordedStoragePath() throws Exception {
        ChunkInfo old = row("1", md5); old.setStoragePath("chunks/1/md5/recorded-path");
        when(chunks.findByUserIdAndFileMd5AndChunkIndex("1", "md5", 0)).thenReturn(Optional.of(old));
        upload("1", "demo", md5);
        ArgumentCaptor<StatObjectArgs> args = ArgumentCaptor.forClass(StatObjectArgs.class);
        verify(minio).statObject(args.capture()); assertEquals(old.getStoragePath(), args.getValue().object());
        verify(minio, never()).putObject(any()); verify(chunks, never()).saveAndFlush(any());
    }

    @Test
    void missingObjectRepairsOldRowAndUploadsAgain() throws Exception {
        when(chunks.findByUserIdAndFileMd5AndChunkIndex("1", "md5", 0)).thenReturn(Optional.of(row("1", md5)));
        when(minio.statObject(any())).thenThrow(storageError("NoSuchKey"));
        upload("1", "demo", md5);
        var order = inOrder(chunks, minio);
        order.verify(chunks).delete(any(ChunkInfo.class)); order.verify(chunks).flush();
        order.verify(chunks).saveAndFlush(any()); order.verify(minio).putObject(any());
    }

    @Test
    void orphanObjectIsRewrittenRatherThanAssumedUploaded() throws Exception {
        when(minio.statObject(any())).thenReturn(mock(StatObjectResponse.class));
        upload("1", "demo", md5);
        verify(minio).putObject(any()); verify(chunks).saveAndFlush(any()); verify(minio, never()).statObject(any());
    }

    @Test
    void differentChecksumForSameIdentityIsConflict() {
        when(chunks.findByUserIdAndFileMd5AndChunkIndex("1", "md5", 0)).thenReturn(Optional.of(row("1", md5)));
        assertEquals(409, assertThrows(CustomException.class,
                () -> upload("1", "other", DigestUtils.md5Hex("other"))).getStatus().value());
        verifyNoInteractions(minio); verify(chunks, never()).saveAndFlush(any()); verify(chunks, never()).delete(any());
    }

    @Test
    void differentUsersHaveSeparateRowsAndObjects() throws Exception {
        upload("1", "demo", md5); upload("2", "demo", md5);
        ArgumentCaptor<PutObjectArgs> objects = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio, times(2)).putObject(objects.capture());
        assertEquals(List.of("chunks/1/md5/0", "chunks/2/md5/0"), objects.getAllValues().stream().map(PutObjectArgs::object).toList());
        ArgumentCaptor<ChunkInfo> rows = ArgumentCaptor.forClass(ChunkInfo.class);
        verify(chunks, times(2)).saveAndFlush(rows.capture());
        assertEquals(List.of("1", "2"), rows.getAllValues().stream().map(ChunkInfo::getUserId).toList());
    }

    @Test
    void uniqueViolationRechecksMatchingWinnerBeforeSuccess() throws Exception {
        when(chunks.findByUserIdAndFileMd5AndChunkIndex("1", "md5", 0))
                .thenReturn(Optional.empty(), Optional.of(row("1", md5)));
        when(chunks.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        upload("1", "demo", md5);
        verify(minio, never()).putObject(any()); verify(transactions).rollback(any());
    }

    @Test
    void uniqueViolationWithDifferentWinnerIsConflict() {
        when(chunks.findByUserIdAndFileMd5AndChunkIndex("1", "md5", 0))
                .thenReturn(Optional.empty(), Optional.of(row("1", DigestUtils.md5Hex("other"))));
        when(chunks.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        assertEquals(409, assertThrows(CustomException.class, () -> upload("1", "demo", md5)).getStatus().value());
        verifyNoInteractions(minio);
    }

    @Test
    void unrelatedConstraintFailureIsNotSilentlyAccepted() {
        when(chunks.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("invalid column"));
        assertThrows(DataIntegrityViolationException.class, () -> upload("1", "demo", md5)); verifyNoInteractions(minio);
    }

    @Test
    void storagePermissionFailureDoesNotDeleteMetadata() throws Exception {
        when(chunks.findByUserIdAndFileMd5AndChunkIndex("1", "md5", 0)).thenReturn(Optional.of(row("1", md5)));
        when(minio.statObject(any())).thenThrow(storageError("AccessDenied"));
        assertEquals(503, assertThrows(CustomException.class, () -> upload("1", "demo", md5)).getStatus().value());
        verify(chunks, never()).delete(any()); verify(minio, never()).putObject(any());
    }

    @Test
    void statusOnlyUsesCurrentUsersDatabaseIndexes() {
        when(chunks.findChunkIndexesByUserIdAndFileMd5("1", "md5")).thenReturn(List.of(0, 3));
        assertEquals(List.of(0, 3), service.getUploadedChunks("md5", "1"));
        verify(chunks).findChunkIndexesByUserIdAndFileMd5("1", "md5"); verifyNoInteractions(files, minio, transactions);
    }

    @Test
    void completedAndMergingUploadsRemainRejected() {
        FileUpload file = files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1").orElseThrow();
        for (int status : List.of(FileUpload.STATUS_COMPLETED, FileUpload.STATUS_MERGING)) {
            file.setStatus(status);
            assertEquals(409, assertThrows(CustomException.class, () -> upload("1", "demo", md5)).getStatus().value());
        }
        verifyNoInteractions(chunks, minio);
    }

    @Test
    void mergeStillQueriesAndCleansOnlyCurrentUsersChunks() throws Exception {
        when(chunks.findByUserIdAndFileMd5OrderByChunkIndexAsc("1", "md5")).thenReturn(List.of(row("1", md5)));
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1024L);
        when(minio.statObject(any())).thenReturn(stat);
        when(minio.getPresignedObjectUrl(any())).thenReturn("https://example.com/merged/md5");
        assertEquals("https://example.com/merged/md5", service.mergeChunks("md5", "test.pdf", "1"));
        verify(minio, never()).composeObject(any());
        verify(chunks).deleteByUserIdAndFileMd5("1", "md5");
    }

    private void upload(String userId, String content, String checksum) throws Exception {
        service.uploadChunk("md5", 0, 1024, "test.pdf",
                new MockMultipartFile("file", "test.pdf", "application/pdf", content.getBytes()), "TEAM_A", false, userId, checksum);
    }
    private ChunkInfo row(String userId, String checksum) {
        ChunkInfo row = new ChunkInfo(); row.setId(1L); row.setUserId(userId); row.setFileMd5("md5");
        row.setChunkIndex(0); row.setChunkMd5(checksum); row.setStoragePath("chunks/" + userId + "/md5/0"); return row;
    }
    private ErrorResponseException storageError(String code) {
        return new ErrorResponseException(new ErrorResponse(code, "test", "uploads", "chunk", null, null, null), null, "test");
    }
}
