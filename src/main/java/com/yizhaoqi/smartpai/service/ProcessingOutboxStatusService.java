package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.ProcessingOutbox;
import com.yizhaoqi.smartpai.repository.ProcessingOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
public class ProcessingOutboxStatusService {
    private final ProcessingOutboxRepository outbox;
    public ProcessingOutboxStatusService(ProcessingOutboxRepository outbox) { this.outbox = outbox; }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markSent(Long id) {
        outbox.markSent(id, ProcessingOutbox.Status.PENDING, ProcessingOutbox.Status.SENT, LocalDateTime.now());
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long id, String error) {
        String message = error == null ? "Unknown delivery failure" : error;
        outbox.recordFailure(id, ProcessingOutbox.Status.PENDING, message.substring(0, Math.min(message.length(), 1000)));
    }
}
