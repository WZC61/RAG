package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import com.yizhaoqi.smartpai.repository.ProcessingOutboxRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
public class UploadCompletionService {
    private final FileUploadRepository files;
    private final ProcessingOutboxRepository outbox;
    private final FileContentRepository contents;
    private final ObjectMapper mapper;

    public UploadCompletionService(FileUploadRepository files, FileContentRepository contents,
                                   ProcessingOutboxRepository outbox, ObjectMapper mapper) {
        this.files = files;
        this.contents = contents;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    public static String initialEventId(String fileMd5, long generation) {
        return FileProcessingTask.TASK_TYPE_PROCESS_CONTENT + ":" + fileMd5 + ":" + generation;
    }

    /** Only MySQL changes belong here. The caller has already confirmed the final object. */
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void complete(String userId, String fileMd5) throws JsonProcessingException {
        completeInternal(userId, fileMd5, false);
    }

    /** The init caller has verified merged object existence and size, so UPLOADING can complete too. */
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void completeInstantUpload(String userId, String fileMd5) throws JsonProcessingException {
        completeInternal(userId, fileMd5, true);
    }

    private void completeInternal(String userId, String fileMd5, boolean instant) throws JsonProcessingException {
        FileUpload reference = files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                .orElseThrow(() -> new CustomException("文件记录不存在", HttpStatus.NOT_FOUND));
        String objectPath = "merged/" + fileMd5;
        contents.ensureContent(fileMd5, objectPath, reference.getTotalSize());
        FileContent content = contents.findForUpdate(fileMd5).orElseThrow();
        // Every relation mutation takes the content lock before the relation lock.
        FileUpload file = files.findForUploadCompletion(userId, fileMd5)
                .orElseThrow(() -> new CustomException("文件记录不存在", HttpStatus.NOT_FOUND));
        if (file.getStatus() != FileUpload.STATUS_MERGING && file.getStatus() != FileUpload.STATUS_COMPLETED
                && !(instant && file.getStatus() == FileUpload.STATUS_UPLOADING)) {
            throw new CustomException("文件状态不允许完成，请重新发起 merge", HttpStatus.CONFLICT);
        }
        if (instant && file.getStatus() == FileUpload.STATUS_MERGING) {
            throw new CustomException("文件正在合并中，请稍后重试", HttpStatus.CONFLICT);
        }
        if (content.getTotalSize() != file.getTotalSize() || !objectPath.equals(content.getObjectPath())) {
            throw new CustomException("文件内容元数据与最终对象不一致", HttpStatus.CONFLICT);
        }
        if (content.getDeletedAt() != null) {
            content.setDeletedAt(null);
            content.setProcessingStatus(FileContent.ProcessingStatus.MERGED);
            content.setProcessingError(null);
        }
        String eventId = initialEventId(fileMd5, content.getProcessingGeneration());
        ProcessingOutbox event = null;
        if (content.getProcessingStatus() != FileContent.ProcessingStatus.INDEXED
                && outbox.findByEventId(eventId).isEmpty()) {
            event = new ProcessingOutbox();
            event.setEventId(eventId);
            event.setFileMd5(fileMd5);
            event.setEventType(FileProcessingTask.TASK_TYPE_PROCESS_CONTENT);
            event.setPayload(mapper.writeValueAsString(new ProcessingOutboxPayload(eventId, fileMd5,
                    objectPath, content.getProcessingGeneration(), file.getFileName(), userId)));
        }
        boolean changed = file.getStatus() != FileUpload.STATUS_COMPLETED;
        if (changed) {
            file.setStatus(FileUpload.STATUS_COMPLETED);
            file.setMergedAt(LocalDateTime.now());
        }
        files.saveAndFlush(file);
        if (event != null) outbox.saveAndFlush(event);
        if (changed) outbox.saveAndFlush(AclChangedPayload.event(mapper, fileMd5, null));
    }
}
