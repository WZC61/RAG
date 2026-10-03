-- MySQL 8. Run before enabling the new Dispatcher, with uploads and consumers stopped.
-- This script does NOT verify MinIO/ES or rewrite existing Kafka messages.
CREATE TABLE IF NOT EXISTS file_content (
    id BIGINT NOT NULL AUTO_INCREMENT,
    file_md5 VARCHAR(32) NOT NULL,
    object_path VARCHAR(255) NOT NULL,
    total_size BIGINT NOT NULL,
    processing_status VARCHAR(16) NOT NULL DEFAULT 'MERGED',
    processing_error VARCHAR(1000) DEFAULT NULL,
    processing_generation BIGINT NOT NULL DEFAULT 1,
    estimated_embedding_tokens BIGINT DEFAULT NULL,
    estimated_chunk_count INT DEFAULT NULL,
    actual_embedding_tokens BIGINT DEFAULT NULL,
    actual_chunk_count INT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    indexed_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_content_md5 (file_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE processing_outbox MODIFY user_id VARCHAR(64) NULL;

-- The legacy base DDL does not contain vectorization_status; deployed Hibernate schemas may.
SET @has_legacy_status = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'file_upload' AND column_name = 'vectorization_status');
SET @status_expr = IF(@has_legacy_status > 0,
    'CASE WHEN MAX(vectorization_status = ''COMPLETED'') = 1 THEN ''INDEXED'' WHEN MAX(vectorization_status = ''FAILED'') = 1 THEN ''FAILED'' ELSE ''MERGED'' END',
    '''MERGED''');
SET @has_legacy_error = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'file_upload' AND column_name = 'vectorization_error_message');
SET @error_expr = IF(@has_legacy_status > 0 AND @has_legacy_error > 0,
    'CASE WHEN MAX(vectorization_status = ''COMPLETED'') = 1 THEN NULL ELSE MAX(CASE WHEN vectorization_status = ''FAILED'' THEN vectorization_error_message END) END',
    'NULL');
SET @backfill = CONCAT(
    'INSERT INTO file_content (file_md5, object_path, total_size, processing_status, processing_error, processing_generation, ',
    'estimated_embedding_tokens, estimated_chunk_count, actual_embedding_tokens, actual_chunk_count, created_at, updated_at) ',
    'SELECT file_md5, CONCAT(''merged/'', file_md5), MIN(total_size), ', @status_expr,
    ', ', @error_expr, ', 1, MAX(estimated_embedding_tokens), MAX(estimated_chunk_count), MAX(actual_embedding_tokens), ',
    'MAX(actual_chunk_count), MIN(created_at), CURRENT_TIMESTAMP FROM file_upload WHERE status = 1 ',
    'GROUP BY file_md5 HAVING MIN(total_size) = MAX(total_size) ',
    'ON DUPLICATE KEY UPDATE file_md5 = VALUES(file_md5)');
PREPARE backfill_statement FROM @backfill;
EXECUTE backfill_statement;
DEALLOCATE PREPARE backfill_statement;

-- Convert unsent user events to one content event per generation, without dropping a pending intent.
-- SENT legacy rows remain historical records. Existing Kafka backlog must be drained before rollout.
START TRANSACTION;
INSERT INTO processing_outbox (event_id, file_md5, event_type, payload, status, retry_count, created_at)
SELECT CONCAT('PROCESS_CONTENT:', c.file_md5, ':', c.processing_generation), c.file_md5,
    'PROCESS_CONTENT', JSON_OBJECT('eventId', CONCAT('PROCESS_CONTENT:', c.file_md5, ':', c.processing_generation),
        'fileMd5', c.file_md5, 'objectPath', c.object_path, 'processingGeneration', c.processing_generation,
        'fileName', JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.fileName')), 'requesterId', o.user_id),
    'PENDING', 0, CURRENT_TIMESTAMP
FROM file_content c JOIN processing_outbox o ON o.id = (
    SELECT MIN(old.id) FROM processing_outbox old
    WHERE old.file_md5 = c.file_md5 AND old.event_type = 'UPLOAD_PROCESS' AND old.status = 'PENDING')
WHERE c.processing_status <> 'INDEXED'
ON DUPLICATE KEY UPDATE event_id = VALUES(event_id);

DELETE old FROM processing_outbox old JOIN file_content c ON c.file_md5 = old.file_md5
WHERE old.event_type = 'UPLOAD_PROCESS' AND old.status = 'PENDING'
  AND (c.processing_status = 'INDEXED' OR EXISTS (
      SELECT 1 FROM (SELECT DISTINCT event_id FROM processing_outbox) converted
      WHERE converted.event_id = CONCAT('PROCESS_CONTENT:', c.file_md5, ':', c.processing_generation)));
COMMIT;

-- Resolve these conflicting historical sizes manually; they were intentionally excluded from backfill.
SELECT file_md5, MIN(total_size), MAX(total_size) FROM file_upload
WHERE status = 1 GROUP BY file_md5 HAVING MIN(total_size) <> MAX(total_size);
