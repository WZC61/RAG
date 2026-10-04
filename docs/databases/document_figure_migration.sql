-- MySQL 8. Additive migration: no historical vector/content rows are rewritten.
-- Run before starting the Consumer with the PP PDF persistence pipeline.
CREATE TABLE IF NOT EXISTS document_figures (
    id BIGINT NOT NULL AUTO_INCREMENT,
    file_md5 VARCHAR(32) NOT NULL,
    processing_generation BIGINT NOT NULL,
    page_number INT NOT NULL,
    figure_index INT NOT NULL,
    figure_label VARCHAR(255) DEFAULT NULL,
    image_path VARCHAR(255) NOT NULL,
    bbox LONGTEXT DEFAULT NULL,
    caption LONGTEXT DEFAULT NULL,
    ocr_text LONGTEXT DEFAULT NULL,
    nearby_text LONGTEXT DEFAULT NULL,
    description LONGTEXT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_figure_identity (file_md5, processing_generation, page_number, figure_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容级解析 Figure，原图路径指向 MinIO';
