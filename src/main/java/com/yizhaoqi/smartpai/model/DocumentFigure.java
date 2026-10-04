package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Content-level figure metadata. External PP resource URLs are deliberately not persisted. */
@Data
@Entity
@Table(name = "document_figures", uniqueConstraints = @UniqueConstraint(
        name = "uk_document_figure_identity",
        columnNames = {"file_md5", "processing_generation", "page_number", "figure_index"}))
public class DocumentFigure {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;
    // Matches FileContent / FileProcessingTask generation without narrowing to an int.
    @Column(name = "processing_generation", nullable = false)
    private Long processingGeneration;
    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;
    @Column(name = "figure_index", nullable = false)
    private Integer figureIndex;
    @Column(name = "figure_label", length = 255)
    private String figureLabel;
    @Column(name = "image_path", nullable = false, length = 255)
    private String imagePath;
    @Lob
    private String bbox;
    @Lob
    private String caption;
    @Lob
    @Column(name = "ocr_text")
    private String ocrText;
    @Lob
    @Column(name = "nearby_text")
    private String nearbyText;
    @Lob
    private String description;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
