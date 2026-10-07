package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.repository.FileContentRepository;
import com.yizhaoqi.smartpai.config.MinioConfig;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.ChunkInfo;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import io.micrometer.common.util.StringUtils;
import io.minio.*;
import io.minio.http.Method;
import io.minio.GetObjectResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.LocalDateTime;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class UploadService {

    private static final Logger logger = LoggerFactory.getLogger(UploadService.class);
    private static final ConcurrentHashMap<String, Object> FILE_UPLOAD_CREATE_LOCKS = new ConcurrentHashMap<>();
    private static final long MERGE_LOCK_WAIT_SECONDS = 30;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private UploadCompletionService uploadCompletionService;

    // 分片事务使用 JPA 数据库事务管理器
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("transactionManager")
    private PlatformTransactionManager transactionManager;

    // 用于与 MinIO 服务器交互
    @Autowired
    private MinioClient minioClient;

    // 用于操作文件上传记录的 Repository
    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private FileContentRepository fileContentRepository;

    // 用于操作分片信息的 Repository
    @Autowired
    private ChunkInfoRepository chunkInfoRepository;

    @Autowired
    private MinioConfig minioConfig;

    /** Initialize the user's upload without touching chunks, Redis or the async pipeline. */
    public FileUpload initializeUpload(String fileMd5, long totalSize, String fileName,
                                       String orgTag, boolean isPublic, String userId) {
        boolean mergedExists = mergedObjectExists(fileMd5, totalSize);
        FileUpload fileUpload = getOrCreateFileUpload(fileMd5, totalSize, fileName, orgTag, isPublic, userId, getFileType(fileName));
        // A newly registered reference protects storage. Recheck after waiting for a
        // concurrent cleanup so init never trusts an object observed before that lock.
        if (mergedExists) mergedExists = mergedObjectExists(fileMd5, totalSize);
        if (fileUpload.getStatus() == FileUpload.STATUS_MERGING) {
            throw new CustomException("文件正在合并中，请稍后重试", HttpStatus.CONFLICT);
        }

        if (mergedExists) {
            try {
                uploadCompletionService.completeInstantUpload(userId, fileMd5);
                return fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId).orElseThrow();
            } catch (CustomException e) {
                throw e;
            } catch (Exception e) {
                throw new CustomException("秒传完成事务失败，请重试", HttpStatus.SERVICE_UNAVAILABLE);
            }
        }
        int targetStatus = FileUpload.STATUS_UPLOADING;
        if (fileUpload.getStatus() != targetStatus) {
            fileUploadRepository.updateUploadCompletionIfCurrent(fileUpload.getId(), fileUpload.getStatus(), targetStatus,
                    mergedExists ? LocalDateTime.now() : null);
            fileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                    .orElseThrow(() -> new CustomException("文件记录不存在，请重试", HttpStatus.CONFLICT));
            if (fileUpload.getStatus() != targetStatus) {
                throw new CustomException("文件状态已变化，请稍后重试", HttpStatus.CONFLICT);
            }
        }
        return fileUpload;
    }

    /** Repair content/event metadata even when a repeated merge sees an already completed upload. */
    public void coordinateCompletedUpload(String fileMd5, String userId) throws Exception {
        FileUpload file = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                .orElseThrow(() -> new CustomException("文件记录不存在", HttpStatus.NOT_FOUND));
        if (!mergedObjectExists(fileMd5, file.getTotalSize())) {
            throw new CustomException("最终文件不存在，请重新初始化上传", HttpStatus.CONFLICT);
        }
        uploadCompletionService.completeInstantUpload(userId, fileMd5);
    }

    private boolean mergedObjectExists(String fileMd5, long totalSize) {
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket("uploads").object("merged/" + fileMd5).build());
            if (stat.size() != totalSize) {
                throw new CustomException("文件大小与已存储文件不一致", HttpStatus.CONFLICT);
            }
            return true;
        } catch (io.minio.errors.ErrorResponseException e) {
            String code = e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return false;
            }
            logger.error("检查最终文件失败 => fileMd5: {}, code: {}", fileMd5, code, e);
            throw new CustomException("文件存储暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            logger.error("检查最终文件失败 => fileMd5: {}", fileMd5, e);
            throw new CustomException("文件存储暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /** Verify the received bytes before any chunk metadata or object writes. */
    public void uploadChunk(String fileMd5, int chunkIndex, long totalSize, String fileName,
                            MultipartFile file, String orgTag, boolean isPublic, String userId,
                            String chunkMd5) throws IOException {
        if (chunkIndex < 0 || chunkMd5 == null || !chunkMd5.matches("[a-fA-F0-9]{32}")) {
            throw new CustomException("分片索引或 chunkMd5 无效", HttpStatus.BAD_REQUEST);
        }
        byte[] bytes = file.getBytes();
        String actualChunkMd5 = DigestUtils.md5Hex(bytes);
        if (!actualChunkMd5.equalsIgnoreCase(chunkMd5)) {
            throw new CustomException("分片 MD5 校验失败，请重新上传", HttpStatus.BAD_REQUEST);
        }

        FileUpload fileUpload = getOrCreateFileUpload(fileMd5, totalSize, fileName, orgTag, isPublic, userId, getFileType(fileName));
        if (fileUpload.getStatus() == FileUpload.STATUS_MERGING) {
            throw new CustomException("文件正在合并中，请稍后重试", HttpStatus.CONFLICT);
        }
        if (fileUpload.getStatus() == FileUpload.STATUS_COMPLETED) {
            throw new CustomException("文件已完成合并，不允许继续上传分片", HttpStatus.CONFLICT);
        }

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            transaction.executeWithoutResult(status -> persistChunk(userId, fileMd5, chunkIndex, actualChunkMd5, bytes, file.getContentType()));
        } catch (DataIntegrityViolationException duplicate) {
            // A competing insert must finish before its unique-key failure is reported.
            // Re-read in a new transaction; never treat an arbitrary constraint failure as success.
            transaction.executeWithoutResult(status -> {
                if (chunkInfoRepository.findByUserIdAndFileMd5AndChunkIndex(userId, fileMd5, chunkIndex).isEmpty()) {
                    throw duplicate;
                }
                persistChunk(userId, fileMd5, chunkIndex, actualChunkMd5, bytes, file.getContentType());
            });
        }
    }

    private void persistChunk(String userId, String fileMd5, int chunkIndex, String actualChunkMd5,
                              byte[] bytes, String contentType) {
        Optional<ChunkInfo> existing = chunkInfoRepository.findByUserIdAndFileMd5AndChunkIndex(userId, fileMd5, chunkIndex);
        if (existing.isPresent()) {
            ChunkInfo chunk = existing.get();
            if (!actualChunkMd5.equalsIgnoreCase(chunk.getChunkMd5())) {
                throw new CustomException("同一分片身份已存在不同内容", HttpStatus.CONFLICT);
            }
            if (chunkObjectExists(chunk.getStoragePath())) {
                return;
            }
            // The row is locked until repair commits, so another request cannot replace it underneath us.
            chunkInfoRepository.delete(chunk);
            chunkInfoRepository.flush();
        }

        String path = buildChunkStoragePath(userId, fileMd5, chunkIndex);
        ChunkInfo chunk = new ChunkInfo();
        chunk.setUserId(userId);
        chunk.setFileMd5(fileMd5);
        chunk.setChunkIndex(chunkIndex);
        chunk.setChunkMd5(actualChunkMd5);
        chunk.setStoragePath(path);
        // Reserve the unique identity before writing the fixed object path. This row is
        // invisible to status until MinIO succeeds and the transaction commits.
        chunkInfoRepository.saveAndFlush(chunk);
        try (ByteArrayInputStream stream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder().bucket("uploads").object(path)
                    .stream(stream, bytes.length, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType).build());
        } catch (Exception e) {
            logger.error("写入分片对象失败 => userId: {}, fileMd5: {}, chunkIndex: {}", userId, fileMd5, chunkIndex, e);
            throw new CustomException("分片存储失败，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
    /**
     * 根据文件名获取文件类型
     *
     * @param fileName 文件名
     * @return 文件类型
     */
    private String getFileType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unknown";
        }
        
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "unknown";
        }
        
        String extension = fileName.substring(lastDotIndex + 1).toLowerCase();
        
        // 根据文件扩展名返回文件类型
        switch (extension) {
            case "pdf":
                return "PDF文档";
            case "doc":
            case "docx":
                return "Word文档";
            case "xls":
            case "xlsx":
                return "Excel表格";
            case "ppt":
            case "pptx":
                return "PowerPoint演示文稿";
            case "txt":
                return "文本文件";
            case "md":
                return "Markdown文档";
            case "jpg":
            case "jpeg":
                return "JPEG图片";
            case "png":
                return "PNG图片";
            case "gif":
                return "GIF图片";
            case "bmp":
                return "BMP图片";
            case "svg":
                return "SVG图片";
            case "mp4":
                return "MP4视频";
            case "avi":
                return "AVI视频";
            case "mov":
                return "MOV视频";
            case "wmv":
                return "WMV视频";
            case "mp3":
                return "MP3音频";
            case "wav":
                return "WAV音频";
            case "flac":
                return "FLAC音频";
            case "zip":
                return "ZIP压缩包";
            case "rar":
                return "RAR压缩包";
            case "7z":
                return "7Z压缩包";
            case "tar":
                return "TAR压缩包";
            case "gz":
                return "GZ压缩包";
            case "json":
                return "JSON文件";
            case "xml":
                return "XML文件";
            case "csv":
                return "CSV文件";
            case "html":
            case "htm":
                return "HTML文件";
            case "css":
                return "CSS文件";
            case "js":
                return "JavaScript文件";
            case "java":
                return "Java源码";
            case "py":
                return "Python源码";
            case "cpp":
            case "c":
                return "C/C++源码";
            case "sql":
                return "SQL文件";
            default:
                return extension.toUpperCase() + "文件";
        }
    }

    /** MySQL is the only source of successfully persisted chunk indexes. */
    public List<Integer> getUploadedChunks(String fileMd5, String userId) {
        return chunkInfoRepository.findChunkIndexesByUserIdAndFileMd5(userId, fileMd5);
    }
    /**
     * 获取文件的总分片数
     *
     * @param fileMd5 文件的 MD5 值
     * @param userId 用户ID
     * @return 文件的总分片数
     */
    public int getTotalChunks(String fileMd5, String userId) {
        logger.info("计算文件总分片数 => fileMd5: {}, userId: {}", fileMd5, userId);
        try {
            Optional<FileUpload> fileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId);
            
            if (fileUpload.isEmpty()) {
                logger.warn("文件记录不存在，无法计算分片数 => fileMd5: {}, userId: {}", fileMd5, userId);
                return 0;
            }
            
            long totalSize = fileUpload.get().getTotalSize();
            // 默认每个分片5MB
            int chunkSize = 5 * 1024 * 1024;
            int totalChunks = (int) Math.ceil((double) totalSize / chunkSize);
            
            logger.info("文件总分片数计算结果 => fileMd5: {}, userId: {}, totalSize: {}, chunkSize: {}, totalChunks: {}", 
                      fileMd5, userId, totalSize, chunkSize, totalChunks);
            return totalChunks;
        } catch (Exception e) {
            logger.error("计算文件总分片数失败 => fileMd5: {}, userId: {}, 错误: {}", fileMd5, userId, e.getMessage(), e);
            throw new RuntimeException("Failed to calculate total chunks", e);
        }
    }

    /**
     * 合并所有分片
     *
     * @param fileMd5 文件的 MD5 值
     * @param fileName 文件名
     * @param userId 用户ID
     * @return 合成文件的访问 URL
     */
    public String mergeChunks(String fileMd5, String fileName, String userId) {
        FileUpload file = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                .orElseThrow(() -> new CustomException("文件记录不存在", HttpStatus.NOT_FOUND));
        List<ChunkInfo> chunks = chunkInfoRepository.findByUserIdAndFileMd5OrderByChunkIndexAsc(userId, fileMd5);
        int expectedChunks = getTotalChunks(fileMd5, userId);
        if (expectedChunks <= 0 || chunks.size() != expectedChunks) {
            throw new CustomException("文件分片未全部上传，无法合并", HttpStatus.BAD_REQUEST);
        }
        for (int index = 0; index < expectedChunks; index++) {
            ChunkInfo chunk = chunks.get(index);
            if (chunk.getChunkIndex() != index || !userId.equals(chunk.getUserId())
                    || !fileMd5.equals(chunk.getFileMd5())
                    || !buildChunkStoragePath(userId, fileMd5, index).equals(chunk.getStoragePath())) {
                throw new CustomException("文件分片索引或归属不完整，无法合并", HttpStatus.BAD_REQUEST);
            }
            if (!chunkObjectExists(chunk.getStoragePath())) {
                throw new CustomException("分片 " + index + " 不存在，请重新上传", HttpStatus.BAD_REQUEST);
            }
        }

        // Fast path: reuse only after this user's complete source chunks are checked.
        if (mergedObjectExists(fileMd5, file.getTotalSize())) {
            return completeMerge(file, chunks);
        }

        RLock lock = null;
        boolean acquired = false;
        try {
            lock = redissonClient.getLock("upload:merge:" + fileMd5);
            // No fixed lease: Redisson's watchdog renews ownership while compose is running.
            acquired = lock.tryLock(MERGE_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new CustomException("等待文件合并锁超时，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
            }
            if (!mergedObjectExists(fileMd5, file.getTotalSize())) {
                List<ComposeSource> sources = chunks.stream()
                        .map(chunk -> ComposeSource.builder().bucket("uploads").object(chunk.getStoragePath()).build())
                        .collect(Collectors.toList());
                minioClient.composeObject(ComposeObjectArgs.builder().bucket("uploads")
                        .object("merged/" + fileMd5).sources(sources).build());
                if (!mergedObjectExists(fileMd5, file.getTotalSize())) {
                    throw new CustomException("合并后最终文件不存在，请重试", HttpStatus.SERVICE_UNAVAILABLE);
                }
            }
            return completeMerge(file, chunks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException("等待文件合并锁被中断，请重试", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            logger.error("文件合并或分布式锁操作失败 => fileMd5: {}, userId: {}", fileMd5, userId, e);
            throw new CustomException("文件合并或锁服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            if (acquired && lock != null) {
                try {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                } catch (Exception releaseError) {
                    // Do not mask the merge result or unlock a different thread's ownership.
                    logger.warn("释放文件合并锁失败 => fileMd5: {}, userId: {}", fileMd5, userId, releaseError);
                }
            }
        }
    }

    /** Called only after the final object has been confirmed to exist with the expected size. */
    private String completeMerge(FileUpload file, List<ChunkInfo> chunks) {
        final String url;
        try {
            // URL failures must not destroy the user's retryable source chunks.
            url = generateMergedObjectUrl(file.getFileMd5());
        } catch (Exception e) {
            throw new CustomException("生成文件访问地址失败，请重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            uploadCompletionService.complete(file.getUserId(), file.getFileMd5());
        } catch (Exception e) {
            throw new CustomException("保存上传完成事件失败，请重新发起 merge", HttpStatus.SERVICE_UNAVAILABLE);
        }
        for (ChunkInfo chunk : chunks) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder().bucket("uploads")
                        .object(chunk.getStoragePath()).build());
            } catch (Exception e) {
                logger.warn("删除临时分片失败 => userId: {}, path: {}", file.getUserId(), chunk.getStoragePath(), e);
            }
        }
        try {
            chunkInfoRepository.deleteByUserIdAndFileMd5(file.getUserId(), file.getFileMd5());
        } catch (Exception e) {
            // Completion and PENDING are already committed; cleanup cannot undo them.
            logger.warn("清理已完成上传的分片记录失败 => userId: {}, fileMd5: {}", file.getUserId(), file.getFileMd5(), e);
        }
        return url;
    }

    public GetObjectResponse getMergedFileStream(String fileMd5) throws Exception {
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket("uploads")
                        .object("merged/" + fileMd5)
                        .build()
        );
    }

    public String generateMergedObjectUrl(String fileMd5) throws Exception {
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket("uploads")
                        .object("merged/" + fileMd5)
                        .expiry(1, TimeUnit.HOURS)
                        .build()
        );
    }

    private String buildChunkStoragePath(String userId, String fileMd5, int chunkIndex) {
        return "chunks/" + userId + "/" + fileMd5 + "/" + chunkIndex;
    }

    private boolean chunkObjectExists(String storagePath) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket("uploads").object(storagePath).build());
            return true;
        } catch (io.minio.errors.ErrorResponseException e) {
            String code = e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return false;
            }
            throw new CustomException("分片存储暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (Exception e) {
            throw new CustomException("分片存储暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
    /**
     * 转换为公开 URL
     * @param minioUrl
     * @return
     */
    public String transToPublicUrl(String minioUrl) {
        if (StringUtils.isBlank(minioUrl) || Objects.equals(minioConfig.getEndpoint(), minioConfig.getPublicUrl())) {
            return minioUrl;
        }
        return minioUrl.replaceFirst(minioConfig.getEndpoint(), minioConfig.getPublicUrl());
    }

    private FileUpload getOrCreateFileUpload(String fileMd5,
                                             long totalSize,
                                             String fileName,
                                             String orgTag,
                                             boolean isPublic,
                                             String userId,
                                             String fileType) {
        Optional<FileUpload> existingFileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId);
        if (existingFileUpload.isPresent()) {
            return existingFileUpload.get();
        }

        String lockKey = userId + ":" + fileMd5;
        Object createLock = FILE_UPLOAD_CREATE_LOCKS.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (createLock) {
            try {
                TransactionTemplate creation = new TransactionTemplate(transactionManager);
                creation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                return creation.execute(tx -> {
                    fileContentRepository.findForUpdate(fileMd5);
                    Optional<FileUpload> lockedReference = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId);
                    if (lockedReference.isPresent()) {
                        return lockedReference.get();
                    }

                    logger.info("创建新的文件记录 => fileMd5: {}, fileName: {}, fileType: {}, totalSize: {}, userId: {}, orgTag: {}, isPublic: {}",
                            fileMd5, fileName, fileType, totalSize, userId, orgTag, isPublic);

                    FileUpload fileUpload = new FileUpload();
                    fileUpload.setFileMd5(fileMd5);
                    fileUpload.setFileName(fileName);
                    fileUpload.setTotalSize(totalSize);
                    fileUpload.setStatus(FileUpload.STATUS_UPLOADING);
                    fileUpload.setUserId(userId);
                    fileUpload.setOrgTag(orgTag);
                    fileUpload.setPublic(isPublic);

                    try {
                        return fileUploadRepository.save(fileUpload);
                    } catch (DataIntegrityViolationException e) {
                        throw e;
                    } catch (Exception e) {
                        logger.error("创建文件记录失败 => fileMd5: {}, fileName: {}, fileType: {}, 错误: {}", fileMd5, fileName, fileType, e.getMessage(), e);
                        throw new RuntimeException("创建文件记录失败: " + e.getMessage(), e);
                    }
                });
            } catch (DataIntegrityViolationException duplicate) {
                return fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                        .orElseThrow(() -> duplicate);
            } finally {
                FILE_UPLOAD_CREATE_LOCKS.remove(lockKey, createLock);
            }
        }
    }
}
