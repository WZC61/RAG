# Parsed artifact persistence

FileProcessingConsumer now routes MERGED PROCESS_CONTENT PDFs through DocumentParsingService,
which calls this coordinator. Non-PDF content and legacy ParseService compatibility remain unchanged.

Call `DocumentParsingPersistenceCoordinator.persist(fileMd5, generation, artifacts, chunks, permissions)`
through its Spring proxy, after PP, mapping, assembly and chunking have finished. `true` means a
complete artifact set and PARSED checkpoint committed; `false` means an obsolete or already-finished
task. Download/upload errors and database errors are thrown to the future retry caller.

## Boundaries

The coordinator uses `NOT_SUPPORTED`, suspending any caller MySQL transaction. It first reads the
existing FileContent checkpoint to skip stale/PARSED/INDEXED/FAILED tasks, without claiming ownership.
All figure images are then downloaded and uploaded before the database service is called.

`ParsedArtifactPersistenceService.persist` is a separate `REQUIRES_NEW` transaction using the existing
JPA transaction manager. It locks FileContent, rechecks generation and MERGED status, replaces all
document_vectors and document_figures for this fileMd5, and changes the same content to PARSED,
clearing processingError. Save/flush/commit failures roll back deletions, inserts and the checkpoint.
PARSED/INDEXED are never replaced, FAILED remains terminal, and no processing lease/state is added.

MinIO and MySQL are not atomic. A later figure failure or failed database commit can leave images in
MinIO. Retry for the same generation uses the same content-type-derived keys; a new generation gets
its own directory. No cleanup/Saga/Outbox operation is introduced. Concurrent preparation is not an
ownership claim; same-generation results must correspond to the same immutable source content.

## Images

Keys: `figures/{fileMd5}/{generation}/page-{pageNumber}-figure-{figureIndex}.{ext}`.
Supported formats are JPEG, PNG and WebP only. HTTP Content-Type is diagnostic information, not
proof of the actual image format. The downloaded file signature determines the MinIO Content-Type
and extension (`.jpg`, `.png`, `.webp`), never the signed URL suffix. Missing Content-Type and
application/octet-stream are accepted when the body has a supported signature. HTML/JSON response
types and unsupported signatures are rejected, even if the header claims image/jpeg.
Missing source URLs, non-HTTP(S) URLs, redirects/non-2xx, empty responses, oversize bodies,
download timeout or MinIO failure abort before the commit transaction.
Redirects are deliberately rejected. No Paddle authorization header is sent.

Configuration: `file.parsing.figures.connect-timeout-millis` (10000), `read-timeout-millis` (30000,
socket read inactivity timeout), `max-image-bytes` (10485760). Existing `minio.bucketName` is reused.
This is file-signature validation, not full image decoding or validation of every image structure;
a signature alone cannot prove that an image is complete and decodable.
Temporary source URLs are never stored in figure rows or printed in response diagnostics.

## Legacy compatibility

The current Consumer selects the requester's FileUpload, falling back to the latest FileUpload for
the content. Its userId/orgTag/isPublic must be passed explicitly as LegacyPermissionContext; the
new persistence layer neither guesses an owner nor builds ACL. Initial ParseService rows never set
modelVersion, so new initial rows preserve null. TextChunk indices remain document-level 1-based.
FileContent/DocumentFigure generations use long/BIGINT consistently. Description stays null.

Apply `docs/databases/document_figure_migration.sql` to an existing MySQL schema before rollout.
This change adds a new table/unique key only, and does not migrate historical vector data.
