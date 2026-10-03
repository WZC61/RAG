package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Shared physical content and processing state; access rights remain in FileUpload. */
@Data
@Entity
@Table(name = "file_content", uniqueConstraints =
        @UniqueConstraint(name = "uk_file_content_md5", columnNames = "file_md5"))
public class FileContent {
    public enum ProcessingStatus { MERGED, PARSED, INDEXED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;
    @Column(name = "object_path", nullable = false, length = 255)
    private String objectPath;
    @Column(name = "total_size", nullable = false)
    private long totalSize;
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 16)
    private ProcessingStatus processingStatus = ProcessingStatus.MERGED;
    @Column(name = "processing_error", length = 1000)
    private String processingError;
    @Column(name = "processing_generation", nullable = false)
    private long processingGeneration = 1;
    @Column(name = "estimated_embedding_tokens")
    private Long estimatedEmbeddingTokens;
    @Column(name = "estimated_chunk_count")
    private Integer estimatedChunkCount;
    @Column(name = "actual_embedding_tokens")
    private Long actualEmbeddingTokens;
    @Column(name = "actual_chunk_count")
    private Integer actualChunkCount;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }
}
