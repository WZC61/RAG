package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import com.yizhaoqi.smartpai.exception.CustomException;
import io.minio.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** Rebuild the projection from current relations; repeated/out-of-order events are safe. */
@Service
public class SharedContentAclService {
    private final FileUploadRepository files;
    private final FileContentRepository contents;
    private final ProcessingOutboxRepository outbox;
    private final DocumentVectorRepository texts;
    private final DocumentFigureRepository figures;
    private final ChunkInfoRepository chunks;
    private final ElasticsearchService search;
    private final MinioClient storage;
    private final ObjectMapper mapper;
    private final ContentCleanupCheckpointService cleanupCheckpoint;

    public SharedContentAclService(FileUploadRepository files, FileContentRepository contents,
            ProcessingOutboxRepository outbox, DocumentVectorRepository texts, DocumentFigureRepository figures,
            ChunkInfoRepository chunks, ElasticsearchService search, MinioClient storage, ObjectMapper mapper,
            ContentCleanupCheckpointService cleanupCheckpoint) {
        this.files = files; this.contents = contents; this.outbox = outbox; this.texts = texts;
        this.figures = figures; this.chunks = chunks; this.search = search; this.storage = storage; this.mapper = mapper;
        this.cleanupCheckpoint = cleanupCheckpoint;
    }

    @Transactional(transactionManager = "transactionManager")
    public void deleteReference(String md5, String userId) {
        contents.findForUpdate(md5); // Also serializes with reference creation and index writes.
        FileUpload relation = files.findForUploadCompletion(userId, md5)
                .orElseThrow(() -> new CustomException("文件关系不存在", HttpStatus.NOT_FOUND));
        files.delete(relation);
        files.flush();
        outbox.saveAndFlush(AclChangedPayload.event(mapper, md5, userId));
    }

    @Transactional(transactionManager = "transactionManager")
    public void changePermissions(String md5, String userId, String orgTag, boolean isPublic) {
        contents.findForUpdate(md5);
        FileUpload relation = files.findForUploadCompletion(userId, md5)
                .orElseThrow(() -> new CustomException("文件关系不存在", HttpStatus.NOT_FOUND));
        if (Objects.equals(relation.getOrgTag(), orgTag) && relation.isPublic() == isPublic) return;
        relation.setOrgTag(orgTag);
        relation.setPublic(isPublic);
        files.saveAndFlush(relation);
        outbox.saveAndFlush(AclChangedPayload.event(mapper, md5, null));
    }

    /** The content row is a narrow serialization guard, not a distributed transaction.
     * A separate committed checkpoint survives external failures; artifact cleanup can then retry.
     * Do not hold this lock during parsing or model calls. */
    @Transactional(transactionManager = "transactionManager", rollbackFor = Exception.class)
    public void reconcile(String md5, String deletedUserId) throws Exception {
        // No locks/reads in this outer transaction precede the independent durable checkpoint.
        cleanupCheckpoint.invalidateUnreferenced(md5);
        FileContent content = contents.findForUpdate(md5).orElse(null);
        var relations = files.findAllByFileMd5(md5);
        if (relations.isEmpty() && content != null && content.getDeletedAt() == null) {
            // Last deletion raced between the checkpoint and this lock. Retry preparation first;
            // never delete shared objects while reusable INDEXED metadata could survive rollback.
            throw new IllegalStateException("Content cleanup checkpoint changed; retry reconciliation");
        }
        if (deletedUserId != null && files.countByFileMd5AndUserId(md5, deletedUserId) == 0) {
            for (ChunkInfo chunk : chunks.findByUserIdAndFileMd5OrderByChunkIndexAsc(deletedUserId, md5))
                remove(chunk.getStoragePath());
            removePrefix("chunks/" + deletedUserId + "/" + md5 + "/");
            chunks.deleteByUserIdAndFileMd5(deletedUserId, md5);
        }
        if (!relations.isEmpty()) {
            // UPLOADING references protect storage but never grant access to indexed content.
            search.replaceAcl(md5, ContentAcl.from(relations));
            return;
        }
        search.deleteByFileMd5(md5);
        remove("merged/" + md5);
        removePrefix("figures/" + md5 + "/");
        texts.deleteByFileMd5(md5);
        figures.deleteByFileMd5(md5);
    }

    /** DLT is diagnostic; ACL reconciliation is retried through the existing durable Outbox. */
    @Transactional(transactionManager = "transactionManager")
    public void retryAfterDlt(FileProcessingTask task) {
        var original = outbox.findByEventId(task.getEventId()).orElseThrow();
        if (!FileProcessingTask.TASK_TYPE_ACL_CHANGED.equals(original.getEventType())
                || !original.getFileMd5().equals(task.getFileMd5()))
            throw new IllegalArgumentException("Invalid ACL retry identity");
        outbox.saveAndFlush(AclChangedPayload.event(mapper, task.getFileMd5(), original.getUserId()));
    }

    private void remove(String key) throws Exception {
        storage.removeObject(RemoveObjectArgs.builder().bucket("uploads").object(key).build());
    }

    private void removePrefix(String prefix) throws Exception {
        for (var result : storage.listObjects(ListObjectsArgs.builder().bucket("uploads").prefix(prefix).recursive(true).build()))
            remove(result.get().objectName());
    }
}
