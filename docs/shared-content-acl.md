# Shared content ACL

`file_upload` is the permission source. A completed relation grants its owner access;
a non-empty organization tag grants that organization access, except `PRIVATE_*`,
which is owner-only. Any completed public relation makes the content public.
All relations, including UPLOADING/MERGING, protect physical content from deletion.

Both TEXT and FIGURE carry keyword arrays `allowedUserIds` and `allowedOrgTags`, plus
the existing boolean wire field `public`. Legacy `userId`/`orgTag` are metadata only.
Initial Bulk populates the full union. Every upload completion, permission change,
relation deletion, and successful INDEXED transition commits an ACL_CHANGED Outbox
trigger with its MySQL changes. The event contains identity/optional deleted-owner
cleanup hint, not a snapshot or URL. It uses a new UUID per committed change.
Repeated completion and unchanged permissions emit no redundant change.

Dispatcher sends ACL_CHANGED without generating a MinIO URL, keyed by fileMd5 on
the existing topic. Consumer recomputes current relations and replaces the complete
ACL on all documents with that fileMd5. Old/out-of-order triggers are harmless.
The final INDEXED trigger repairs changes whose earlier event found no ES documents.

Deletion removes only the caller's relation and commits its trigger atomically.
Reconciliation clears only that deleted owner's temporary chunks (provided that
owner has not recreated a relation). Remaining references preserve shared content.
Zero references first commit an independent short MySQL cleanup checkpoint:
FileContent gets deleted_at and FAILED, its generation advances once, and indexedAt
and usage estimates are cleared. Only after this durable invalidation does a second
transaction lock the content row, recheck references, and remove ES documents,
merged object, figure objects, MySQL text chunks and figure rows. External failures
propagate; artifact-row cleanup rolls back, but the committed checkpoint survives.
Retries do not advance the generation again while deleted_at is set.
Reupload clears deleted_at, resumes MERGED at the advanced generation, and creates
a new PROCESS_CONTENT. Delayed tasks from the deleted generation cannot overwrite it.

Reference creation/completion/mutation, ACL reconciliation and final ES Bulk all
coordinate on the FileContent database row. Parsing and model calls remain outside
transactions. ES/MinIO operations during reconciliation, and final ES Bulk, hold this
row briefly to prevent stale ACL overwrites or index resurrection during cleanup.
This is serialization, not atomicity across stores. A cleanup checkpoint failure
stops all external deletion. If the last reference disappears between checkpoint
preparation and the second transaction's lock, reconciliation fails for retry
before deleting shared objects. Repeated deletes repair partial external success
while the content remains unreferenced. A new relation after a partial failure stops
shared cleanup; completion must clear the durable tombstone, return to MERGED, and
emit PROCESS_CONTENT for the advanced generation, even when merged still exists.
It cannot reuse the invalidated INDEXED checkpoint.

Reference creation waits for the cleanup row lock. Once a new reference has been
registered, delayed cleanup rechecks current relations and only updates ACL; it
cannot delete the unversioned merged/{fileMd5} or any generation's images/index.
The existing init recheck handles a merged object observed before registration.
Parsing/model work from obsolete generations cannot commit current artifacts.
Abandoned old-generation images or ES documents can remain after partial cleanup;
current-generation read filters hide them. Orphan collection is deferred.
No lease, inbox or distributed transaction is used.

KNN and BM25, including every fallback and the public compatibility entry point,
share the same ACL filter. A DB-derived fileMd5 scope is also applied before recall
and checked after ES returns. Revoked permissions fail closed while ES catches up;
new grants can temporarily return fewer results until ACL projection is visible.
An unavailable permission database raises retrieval failure, rather than a normal
empty result. Organization membership uses
the existing effective-tag cache; that cache's invalidation contract remains unchanged.

ACL errors receive the existing Kafka listener retries. After confirmed DLT delivery,
a fresh PENDING Outbox reconciliation is written, instead of marking FileContent
FAILED. Permanent storage failures will continue producing retries/DLT diagnostics;
operators must fix the underlying dependency. PROCESS_CONTENT recovery is unchanged.
Kafka commit before SENT persistence may duplicate messages; reconciliation is based
on current facts, so repetition is safe.

`POST /api/v1/documents/{fileMd5}/permissions` updates only the authenticated user's
relation, with `{ "orgTag": null, "isPublic": false }` for private access. Non-admins
may select only their effective organizations (null means private). No UI redesign
is included. Existing legacy REINDEX/retry entry points reject content-level or
multiply-referenced files before destructive work; a content-level rebuild API is deferred.

## Existing development data

Apply `docs/databases/shared_content_acl_migration.sql` before rollout. It adds
deleted_at and emits one backfill ACL trigger per existing fileMd5, idempotently.
EsIndexInitializer adds keyword ACL mappings to an existing knowledge_base index;
no index deletion or re-embedding is needed. With init disabled, apply those two
mapping fields manually before dispatching events. Until backfill completes, private
and organization documents lacking ACL arrays intentionally fail closed.

Limits: single-instance Outbox dispatcher; no instant cross-store atomicity; no
revocation of already-issued presigned URLs (their existing expiry still applies);
no cleanup of unknown historical orphan chunks without database/owner hints; shared
Content metadata and Outbox history are retained. An unfinished upload reference
protects storage until its relation is explicitly deleted; expiry cleanup is deferred.
Large installations need a bounded strategy for the DB fileMd5 terms scope (ES's
existing terms-query limit applies). Extreme Kafka rebalance overlap of parsing/model
work retains the previously accepted boundary; final ES writes and cleanup are serialized.

## Explicitly accepted upload security boundary (A1)

Cross-user instant upload still grants a FileUpload relation based on a known
fileMd5 and size when the shared merged object exists, without receiving that
user's actual file bytes. Knowledge of that identity can grant access to the shared
content. This version explicitly accepts that limitation; it is not content
possession proof and must not be described as a fully secure deduplication protocol.
Init/merge protocols and Proof-of-Possession are unchanged by the A2/A3 fixes.
