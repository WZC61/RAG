-- MySQL 8; additive and rerunnable. No file or parsed data is deleted.
-- Apply before starting this version (ddl-auto=update also adds deleted_at locally).
SET @add_deleted_at = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'file_content' AND column_name = 'deleted_at') = 0,
    'ALTER TABLE file_content ADD COLUMN deleted_at DATETIME DEFAULT NULL',
    'SELECT 1');
PREPARE shared_acl_schema FROM @add_deleted_at;
EXECUTE shared_acl_schema;
DEALLOCATE PREPARE shared_acl_schema;

-- The existing-index mapping must have allowedUserIds/allowedOrgTags as keyword.
-- EsIndexInitializer adds them on startup; it does NOT populate existing documents.
-- Backfill using the normal durable reconciliation route, without model/Embedding calls.
INSERT INTO processing_outbox
    (event_id, file_md5, event_type, payload, status, retry_count, created_at)
SELECT CONCAT('ACL_CHANGED:BACKFILL:v1:', f.file_md5), f.file_md5, 'ACL_CHANGED',
       JSON_OBJECT('eventId', CONCAT('ACL_CHANGED:BACKFILL:v1:', f.file_md5),
                   'fileMd5', f.file_md5, 'deletedUserId', NULL),
       'PENDING', 0, CURRENT_TIMESTAMP
FROM (SELECT DISTINCT file_md5 FROM file_upload) f
WHERE NOT EXISTS (SELECT 1 FROM processing_outbox o
                  WHERE o.event_id = CONCAT('ACL_CHANGED:BACKFILL:v1:', f.file_md5));
