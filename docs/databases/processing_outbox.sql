-- Incremental DDL for existing databases. Apply before deploying the Outbox code.
CREATE TABLE IF NOT EXISTS processing_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(96) NOT NULL,
    file_md5 VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) DEFAULT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_processing_outbox_event (event_id),
    INDEX idx_processing_outbox_pending (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首次文件处理可靠投递事件';
