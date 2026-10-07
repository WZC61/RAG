package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.service.SharedContentAclService;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.service.FileContentProcessingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/** DLT publication is confirmed before the generation is made terminal. */
public class FileProcessingDltRecoverer implements ConsumerRecordRecoverer {
    private final ConsumerRecordRecoverer publisher;
    private final FileContentProcessingService contents;
    private final SharedContentAclService acl;

    public FileProcessingDltRecoverer(ConsumerRecordRecoverer publisher, FileContentProcessingService contents) {
        this(publisher, contents, null);
    }

    public FileProcessingDltRecoverer(ConsumerRecordRecoverer publisher, FileContentProcessingService contents,
            SharedContentAclService acl) {
        this.publisher = publisher;
        this.contents = contents;
        this.acl = acl;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        // The standard publisher waits for send/transaction success. Propagate failures so the
        // error handler seeks/retries; do not mark FAILED before publication or swallow DB errors.
        publisher.accept(record, exception);
        if (record.value() instanceof FileProcessingTask task && task.hasValidContentIdentity()) {
            contents.failed(task.getFileMd5(), task.getProcessingGeneration(), exception);
        } else if (record.value() instanceof FileProcessingTask task && task.hasValidAclIdentity()) {
            if (acl == null) throw new IllegalStateException("Missing ACL recovery service");
            acl.retryAfterDlt(task);
        }
        // Unreadable/raw messages and invalid identities still reach DLT without a state update.
    }
}
