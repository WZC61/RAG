-- MySQL 8. One-time migration for the legacy EMPTY chunk_info table.
-- Select the target database first. Stop uploads/consumers before applying.
-- Check SELECT COUNT(*) FROM chunk_info; it must be 0.
-- If historical chunks exist, determine their owners before migrating;
-- this script does not guess user_id or delete any chunk data.
-- Do not rerun on a table that already has user_id / the new unique index.

ALTER TABLE chunk_info
    ADD COLUMN user_id VARCHAR(64) NOT NULL,
    DROP INDEX uk_file_md5_chunk_index,
    ADD UNIQUE KEY uk_user_file_md5_chunk_index (user_id, file_md5, chunk_index);
