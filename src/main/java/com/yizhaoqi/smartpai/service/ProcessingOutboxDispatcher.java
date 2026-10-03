package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.ProcessingOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** One synchronous scheduler worker per application instance; no claims or leases. */
@Service
public class ProcessingOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ProcessingOutboxDispatcher.class);
    private final ProcessingOutboxRepository outbox;
    private final ProcessingOutboxStatusService status;
    private final KafkaTemplate<String, Object> kafka;
    private final KafkaConfig kafkaConfig;
    private final ObjectMapper mapper;
    private final UploadService uploads;
    @Value("${app.processing-outbox.batch-size:50}")
    private int batchSize = 50;

    public ProcessingOutboxDispatcher(ProcessingOutboxRepository outbox, ProcessingOutboxStatusService status,
            KafkaTemplate<String, Object> kafka, KafkaConfig kafkaConfig, ObjectMapper mapper, UploadService uploads) {
        this.outbox = outbox;
        this.status = status;
        this.kafka = kafka;
        this.kafkaConfig = kafkaConfig;
        this.mapper = mapper;
        this.uploads = uploads;
    }

    @Scheduled(fixedDelayString = "${app.processing-outbox.fixed-delay-ms:5000}")
    public void dispatchPending() {
        for (ProcessingOutbox event : outbox.findByStatusOrderByIdAsc(ProcessingOutbox.Status.PENDING,
                PageRequest.of(0, Math.max(1, batchSize)))) {
            try {
                ProcessingOutboxPayload payload = mapper.readValue(event.getPayload(), ProcessingOutboxPayload.class);
                if (!FileProcessingTask.TASK_TYPE_PROCESS_CONTENT.equals(event.getEventType())
                        || !event.getEventId().equals(payload.eventId())
                        || !event.getFileMd5().equals(payload.fileMd5())
                        || payload.processingGeneration() < 1
                        || !UploadCompletionService.initialEventId(payload.fileMd5(), payload.processingGeneration()).equals(event.getEventId())
                        || !("merged/" + event.getFileMd5()).equals(payload.objectPath())) {
                    throw new IllegalArgumentException("Invalid processing outbox identity or object path");
                }
                String url = uploads.generateMergedObjectUrl(payload.fileMd5());
                FileProcessingTask task = new FileProcessingTask(payload.fileMd5(), url, payload.fileName(),
                        null, null, false, event.getEventType(), payload.requesterId());
                task.setEventId(event.getEventId());
                task.setObjectPath(payload.objectPath());
                task.setProcessingGeneration(payload.processingGeneration());
                // Return only after Kafka transaction commit, not just the asynchronous send enqueue.
                kafka.executeInTransaction(template -> {
                    // All generations and redeliveries of one content share a partition.
                    template.send(kafkaConfig.getFileProcessingTopic(), event.getFileMd5(), task);
                    return true;
                });
                status.markSent(event.getId());
            } catch (Exception failure) {
                log.warn("Outbox delivery failed => eventId: {}", event.getEventId(), failure);
                try {
                    status.recordFailure(event.getId(), failure.getClass().getSimpleName() + ": " + failure.getMessage());
                } catch (Exception persistenceFailure) {
                    log.error("Cannot record outbox failure => eventId: {}", event.getEventId(), persistenceFailure);
                }
            }
        }
    }
}
