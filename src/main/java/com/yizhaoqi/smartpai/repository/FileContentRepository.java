package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.FileContent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.Collection;
import java.util.List;

public interface FileContentRepository extends JpaRepository<FileContent, Long> {
    Optional<FileContent> findByFileMd5(String fileMd5);

    List<FileContent> findByFileMd5InAndProcessingStatusAndDeletedAtIsNull(
            Collection<String> fileMd5s, FileContent.ProcessingStatus status);

    // The unique-key upsert also serializes simultaneous first creators in MySQL.
    // Do not catch a failed JPA insert inside the completion transaction: it is rollback-only.
    @Modifying
    @Query(value = "INSERT INTO file_content (file_md5, object_path, total_size, processing_status, " +
            "processing_generation, created_at, updated_at) VALUES (:md5, :path, :size, 'MERGED', 1, " +
            "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE file_md5 = :md5", nativeQuery = true)
    int ensureContent(@Param("md5") String fileMd5, @Param("path") String objectPath, @Param("size") long totalSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from FileContent c where c.fileMd5 = :md5")
    Optional<FileContent> findForUpdate(@Param("md5") String fileMd5);

    @Transactional
    @Modifying
    @Query("update FileContent c set c.estimatedEmbeddingTokens = :tokens, c.estimatedChunkCount = :chunks, " +
            "c.updatedAt = CURRENT_TIMESTAMP where c.fileMd5 = :md5")
    int updateEstimates(@Param("md5") String fileMd5, @Param("tokens") Long tokens, @Param("chunks") Integer chunks);
}
