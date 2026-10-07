package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.FileUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.LocalDateTime;

@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {
    @Query("select (count(f) > 0) from FileUpload f where f.fileMd5 = :md5 and f.status = 1 and "
            + "(f.userId = :userId or f.isPublic = true or "
            + "(f.orgTag in :tags and substring(f.orgTag, 1, 8) <> 'PRIVATE_'))")
    boolean existsAuthorizedCompletedContent(@Param("md5") String md5, @Param("userId") String userId,
                                            @Param("tags") List<String> tags);

    @Query("select distinct f.fileMd5 from FileUpload f where f.status = 1 and "
            + "(f.userId = :userId or f.isPublic = true or "
            + "(f.orgTag in :tags and substring(f.orgTag, 1, 8) <> 'PRIVATE_'))")
    List<String> findAuthorizedContentIds(@Param("userId") String userId, @Param("tags") List<String> tags);

    @Query("select distinct f.fileMd5 from FileUpload f where f.status = 1 and f.isPublic = true")
    List<String> findPublicContentIds();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FileUpload f where f.userId = :userId and f.fileMd5 = :fileMd5")
    Optional<FileUpload> findForUploadCompletion(@Param("userId") String userId, @Param("fileMd5") String fileMd5);

    @Transactional
    @Modifying
    @Query("update FileUpload f set f.estimatedEmbeddingTokens = :tokens, f.estimatedChunkCount = :chunks where f.id = :id")
    int updateEstimates(@Param("id") Long id, @Param("tokens") Long tokens, @Param("chunks") Integer chunks);

    @Transactional
    @Modifying
    @Query("update FileUpload f set f.vectorizationStatus = :status, f.vectorizationErrorMessage = :error "
            + "where f.id = :id and f.vectorizationStatus is null")
    int backfillLegacyStatus(@Param("id") Long id, @Param("status") String status, @Param("error") String error);

    Optional<FileUpload> findFirstByFileMd5OrderByCreatedAtDesc(String fileMd5);

    List<FileUpload> findAllByFileMd5(String fileMd5);

    List<FileUpload> findAllByVectorizationStatusIsNull();

    List<FileUpload> findAllByFileMd5AndUserIdOrderByCreatedAtDesc(String fileMd5, String userId);

    Optional<FileUpload> findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(String fileMd5, String userId);

    Optional<FileUpload> findFirstByFileMd5AndIsPublicTrueAndStatusOrderByCreatedAtDesc(String fileMd5, int status);

    default Optional<FileUpload> findFirstByFileMd5AndIsPublicTrueOrderByCreatedAtDesc(String fileMd5) {
        return findFirstByFileMd5AndIsPublicTrueAndStatusOrderByCreatedAtDesc(fileMd5, FileUpload.STATUS_COMPLETED);
    }

    Optional<FileUpload> findFirstByFileNameAndIsPublicTrueAndStatusOrderByCreatedAtDesc(String fileName, int status);

    default Optional<FileUpload> findFirstByFileNameAndIsPublicTrueOrderByCreatedAtDesc(String fileName) {
        return findFirstByFileNameAndIsPublicTrueAndStatusOrderByCreatedAtDesc(fileName, FileUpload.STATUS_COMPLETED);
    }

    Optional<FileUpload> findFirstByOrderByMergedAtDesc();
    
    long countByFileMd5(String fileMd5);

    long countByFileMd5AndUserId(String fileMd5, String userId);
    
    @Transactional
    @Modifying
    @Query("delete from FileUpload f where f.fileMd5 = :fileMd5")
    int deleteByFileMd5(@Param("fileMd5") String fileMd5);

    @Transactional
    @Modifying
    @Query("delete from FileUpload f where f.fileMd5 = :fileMd5 and f.userId = :userId")
    int deleteByFileMd5AndUserId(@Param("fileMd5") String fileMd5, @Param("userId") String userId);
    
    /**
     * 查询用户自己的文件和公开文件
     */
    List<FileUpload> findByUserIdOrIsPublicTrue(String userId);
    
    /**
     * 查询用户可访问的所有文件（考虑层级标签权限）
     * 包括：1. 用户自己上传的文件
     *      2. 公开的文件
     *      3. 用户所属组织的文件（包含层级关系）
     *
     * @param userId 用户ID
     * @param orgTagList 用户有效的组织标签列表（包含层级结构）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.status = 1 AND (f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND substring(f.orgTag, 1, 8) <> 'PRIVATE_'))")
    List<FileUpload> findAccessibleFilesWithTags(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
    
    /**
     * 查询用户可访问的所有文件（原始方法，保留向后兼容性）
     * 
     * @param userId 用户ID
     * @param orgTagList 用户所属的组织标签列表（逗号分隔）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.status = 1 AND (f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND substring(f.orgTag, 1, 8) <> 'PRIVATE_'))")
    List<FileUpload> findAccessibleFiles(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
    
    /**
     * 查询用户自己上传的所有文件
     * 
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    List<FileUpload> findByUserId(String userId);

    List<FileUpload> findByUserIdAndFileNameOrderByCreatedAtDesc(String userId, String fileName);

    List<FileUpload> findByFileMd5In(List<String> md5List);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.status = :newStatus WHERE f.id = :id AND f.status = :currentStatus")
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("currentStatus") int currentStatus,
                              @Param("newStatus") int newStatus);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.status = :newStatus, f.mergedAt = :mergedAt WHERE f.id = :id AND f.status = :currentStatus")
    int updateUploadCompletionIfCurrent(@Param("id") Long id,
                                        @Param("currentStatus") int currentStatus,
                                        @Param("newStatus") int newStatus,
                                        @Param("mergedAt") LocalDateTime mergedAt);
}
