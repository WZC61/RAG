package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "processing_outbox",
        uniqueConstraints = @UniqueConstraint(name = "uk_processing_outbox_event", columnNames = "event_id"),
        indexes = @Index(name = "idx_processing_outbox_pending", columnList = "status,id"))
public class ProcessingOutbox {
    public enum Status { PENDING, SENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, length = 96)
    private String eventId;
    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;
    /** Legacy event initiator, never part of PROCESS_CONTENT identity. */
    @Deprecated
    @Column(name = "user_id", length = 64)
    private String userId;
    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;
    @Column(name = "retry_count", nullable = false)
    private int retryCount;
    @Column(name = "last_error", length = 1000)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @PrePersist
    void initializeCreatedAt() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
