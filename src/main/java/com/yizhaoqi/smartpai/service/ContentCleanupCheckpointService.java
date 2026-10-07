package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** Invalidate reuse durably before any shared external deletion can partially succeed. */
@Service
public class ContentCleanupCheckpointService {
    private final FileContentRepository contents;
    private final FileUploadRepository files;

    public ContentCleanupCheckpointService(FileContentRepository contents, FileUploadRepository files) {
        this.contents = contents;
        this.files = files;
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.REQUIRES_NEW)
    public void invalidateUnreferenced(String md5) {
        FileContent content = contents.findForUpdate(md5).orElse(null);
        if (content == null || content.getDeletedAt() != null || files.countByFileMd5(md5) != 0) return;
        // This commit survives a subsequent ES/MinIO deletion failure. Repetition does not bump again.
        content.setProcessingGeneration(Math.addExact(content.getProcessingGeneration(), 1));
        content.setDeletedAt(LocalDateTime.now());
        content.setProcessingStatus(FileContent.ProcessingStatus.FAILED);
        content.setProcessingError("Shared content invalidated for last-reference cleanup");
        content.setIndexedAt(null);
        content.setActualChunkCount(null);
        content.setActualEmbeddingTokens(null);
        content.setEstimatedChunkCount(null);
        content.setEstimatedEmbeddingTokens(null);
        contents.saveAndFlush(content);
    }
}
