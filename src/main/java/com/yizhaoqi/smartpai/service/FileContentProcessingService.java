package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.AclChangedPayload;
import com.yizhaoqi.smartpai.repository.ProcessingOutboxRepository;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.NestedExceptionUtils;
import java.time.LocalDateTime;

/** Short content-state transactions; parsing, embedding and ES writes remain outside them. */
@Service
public class FileContentProcessingService {
    private final FileContentRepository contents;
    private final ProcessingOutboxRepository outbox;
    public FileContentProcessingService(FileContentRepository contents,
            ProcessingOutboxRepository outbox) {
        this.contents = contents; this.outbox = outbox;
    }

    public boolean needsProcessing(String md5, long generation) {
        FileContent.ProcessingStatus status = checkpoint(md5, generation);
        return status == FileContent.ProcessingStatus.MERGED || status == FileContent.ProcessingStatus.PARSED;
    }

    /** Read-only checkpoint, not an ownership claim. Same-key partition ordering is the normal guard. */
    public FileContent.ProcessingStatus checkpoint(String md5, long generation) {
        FileContent content = contents.findByFileMd5(md5).orElseThrow();
        return content.getProcessingGeneration() == generation ? content.getProcessingStatus() : null;
    }

    @Transactional(transactionManager = "transactionManager")
    public void parsed(String md5, long generation) {
        FileContent content = current(md5, generation);
        if (content == null) return;
        content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
        content.setProcessingError(null);
    }

    @Transactional(transactionManager = "transactionManager")
    public void indexed(String md5, long generation, VectorizationService.VectorizationUsageResult usage) {
        FileContent content = current(md5, generation);
        if (content == null) return;
        if (content.getProcessingStatus() != FileContent.ProcessingStatus.PARSED
                || usage == null || usage.actualChunkCount() <= 0)
            throw new IllegalStateException("INDEXED requires PARSED and a non-empty successful index result");
        content.setProcessingStatus(FileContent.ProcessingStatus.INDEXED);
        content.setProcessingError(null);
        content.setActualEmbeddingTokens((long) usage.actualEmbeddingTokens());
        content.setActualChunkCount(usage.actualChunkCount());
        content.setIndexedAt(LocalDateTime.now());
        // Repairs relation changes that occurred while models were running, even if an
        // earlier ACL event found no ES documents. Commit with INDEXED, then dispatch.
        outbox.saveAndFlush(AclChangedPayload.event(
                new ObjectMapper(), md5, null));
    }

    @Transactional(transactionManager = "transactionManager")
    public void recordError(String md5, long generation, Throwable failure) {
        FileContent content = current(md5, generation);
        if (content == null) return;
        // Retry must resume from the last completed stage, not turn the generation terminal.
        content.setProcessingError(errorMessage(failure));
    }

    /** Call only after confirmed DLT publication; never terminate a different generation. */
    @Transactional(transactionManager = "transactionManager")
    public void failed(String md5, long generation, Throwable failure) {
        FileContent content = contents.findForUpdate(md5).orElse(null);
        if (content == null || content.getProcessingGeneration() != generation
                || content.getProcessingStatus() == FileContent.ProcessingStatus.INDEXED) return;
        content.setProcessingStatus(FileContent.ProcessingStatus.FAILED);
        content.setProcessingError(errorMessage(failure));
    }

    private FileContent current(String md5, long generation) {
        FileContent content = contents.findForUpdate(md5).orElseThrow();
        if (content.getProcessingGeneration() != generation
                || content.getProcessingStatus() == FileContent.ProcessingStatus.INDEXED
                || content.getProcessingStatus() == FileContent.ProcessingStatus.FAILED) return null;
        return content;
    }

    private String errorMessage(Throwable failure) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
        String error = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return error.substring(0, Math.min(1000, error.length()));
    }
}
