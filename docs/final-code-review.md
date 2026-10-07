# Final Code Review — feat/multimodal-indexing

Reviewed on 2026-10-07. The working tree, including untracked feature code, is the
authority for this review. No commit was created and no pre-existing changes were
reset. Earlier upload, Outbox and parsing work is partly already in HEAD, so its
behavior was reviewed from source as well as the full current tracked diff.

## Conclusion

The focused A2/A3 follow-up on 2026-10-07 resolves the two correctness blockers
below. A1 is explicitly accepted by the owner for this version, remains unchanged
in code, and is recorded as a security limitation rather than a possession proof.
No new upload protocol, processing state, middleware or distributed transaction
was introduced. No commit has been made.

## Focused A2/A3 follow-up

### A1. Explicitly accepted: instant upload does not prove possession

Files: `UploadService.initializeUpload`, `UploadCompletionService.completeInternal`,
`UploadService.mergeChunks`. Their instant-upload protocol is unchanged.

For an existing `merged/{fileMd5}`, cross-user instant upload uses a known fileMd5
and size to create/complete the caller's FileUpload, without receiving their actual
file bytes. The new relation grants access to the shared content, including its
text, PDF and Figures. Knowing that identity can therefore grant access; read ACL
checks cannot compensate for that initial grant. This is an explicitly accepted
security boundary in this version, **not content possession proof or a fully secure
deduplication protocol**. No Proof-of-Possession implementation is included.

Chunk MD5 checks still do not verify the concatenated file's actual MD5; first
creation can also accept a client-supplied content identity. This existing identity
boundary is recorded without changing init/merge or expanding the current fixes.

### A2. Fixed: modern non-PDF parsing uses the atomic artifact boundary

`FileProcessingConsumer` routes modern non-PDF PROCESS_CONTENT through
`NonPdfDocumentParsingService`, not incremental `parseAndSave`.
`ParseService.parseToChunks` keeps Tika and existing parent-batch/TextChunker behavior,
but collects a complete TextChunk list in memory without repository writes.
Extraction runs outside MySQL. Non-PDF pages remain null rather than invented.

The same `ParsedArtifactPersistenceService` used by PDFs locks FileContent,
rechecks generation/MERGED, atomically replaces Text/Figure rows and commits PARSED.
PDF page validation remains strict in its coordinator. Extraction failure leaves
rows untouched; save/commit failure rolls back the replacement/checkpoint. PARSED
retries do not replace artifacts, and old generations cannot write current rows.
The legacy `ParseService.parseAndSave` compatibility entry point is unchanged.

Regressions cover real Tika extraction without writes, atomic SQL rollback,
same-generation retry, a slow old parser racing a new generation commit, and the
real modern Consumer/Tika/persistence/vector assembly/INDEXED path with external
Embedding/ES test doubles.

### A3. Fixed: durable invalidation precedes shared external deletion

`ContentCleanupCheckpointService` commits a separate REQUIRES_NEW transaction:
when no references exist, lock FileContent, set deleted_at/FAILED, advance generation
once, clear indexedAt/usage. No shared external delete occurs before this succeeds.
`SharedContentAclService.reconcile` then locks content again, rechecks references
and performs cleanup. Its rollback cannot restore the invalidated INDEXED state.
If the last reference disappears between those steps, reconciliation fails for
retry before any shared deletion instead of using an unprepared checkpoint.

After partial cleanup failure, a new upload/instant upload resumes MERGED at the
advanced generation and emits a fresh stable PROCESS_CONTENT event. It reparses
and rebuilds Text/Figure indices even if the old merged object survived. Existing
reference-registration locking and init object recheck are retained unchanged.
While deletion holds the content row lock, registration waits. After a new reference
exists, delayed cleanup only rebuilds ACL; it cannot delete merged/{fileMd5}, new
Figure images, or new-generation ES documents.

The regression uses actual H2 SQL, Spring transactions and concurrent threads:
ES/merged deletion succeeds, Figure deletion fails while B begins init, B uploads
and merges, current-generation artifacts/descriptions and TEXT/FIGURE indices reach
INDEXED, and delayed old cleanup/PROCESS_CONTENT leave the rebuilt resources intact.
MinIO/ES/PP/model calls are deterministic test doubles, not a new live-service
acceptance. Additional tests cover checkpoint rollback before external side effects,
and instant recovery when merged survived a partial ES deletion failure.

## Small issues fixed during this review

- SearchResult/RetrievalResult and ReferenceInfo no longer serialize internal
  imagePath. Reference-detail also omits that field. Internal Java/ES metadata is
  preserved; Figure reads still locate the path from MySQL by business identity.
- Embedding HTTP/retry errors and LLM provider/stream-decoding errors no longer
  log remote response bodies or exception source snippets. Provider errors log
  status or type. Embedding failures do not expose the remote cause chain.
- One Embedding call snapshots its provider once for all batches and reports that
  same model version. Config reload cannot mix dimensions/providers inside a
  multi-batch call. Invalid zero/negative batch size fails immediately.
- Delayed legacy UPLOAD_PROCESS skips files already owned by FileContent or
  referenced by several users, before parsing or legacy status mutations.
  Single-user legacy callers remain compatible; existing REINDEX protection stays.
- Assistant output is escaped before trusted citation HTML is inserted. Current
  chat and history renderers disable arbitrary Markdown attributes. This prevents
  model HTML/iframe or attribute injection while retaining Markdown and citations.
- Corrected the stale Figure-description configuration comment and ACL docs that
  incorrectly described DB retrieval failure as empty results and cleanup retries
  as repairing every partial deletion interleaving.

## B. Small follow-up improvements

- `DocumentVector.anchorText` permits 512 characters but base DDL declares 255.
  Current chunker emits at most 120, so current parsing does not truncate. Align
  the entity/base schema and existing-schema migration when changing this limit.
- Upload init/chunk check the chosen orgTag's size quota but do not check that a
  non-admin belongs to that org. The permissions-edit endpoint does. This permits
  cross-org publishing/quota selection, rather than reading private content by
  itself. Reuse one membership rule at upload authorization when resolving A1.
- The legacy/current LLM streaming parsers log and skip malformed response frames.
  Logs are now safe, but a damaged stream after some valid text can still complete
  with a partial answer. Prefer an explicit stream error for malformed frames;
  keep it separate from a larger streaming protocol rewrite.
- `knowledge_stats` returns global counts to ordinary users. If aggregate metadata
  is considered tenant-private, scope it or restrict this old tool to admins. It
  does not return document text or image paths.
- Do not stage files whose status is only a line-ending/index refresh effect and
  whose actual Git diff is empty. Preserve the current working tree; avoid a
  wholesale cleanup or indiscriminate `git add .`.

## Confirmed main-chain properties

| Area | Current behavior |
| --- | --- |
| Chunks | userId + fileMd5 + index unique identity; actual received-byte chunk MD5 check; stable per-user MinIO path; same chunk reuse and missing-object repair; no Bitmap |
| Merge | Per-user CAS plus global fileMd5 Redisson lock/watchdog, double-check and size validation; MySQL completion/Outbox commit before per-user chunk cleanup |
| Completion/Outbox | FileUpload/FileContent and initial content event in one short transaction; stable PROCESS_CONTENT:MD5:generation; ACL_CHANGED committed with relation/index changes; signed URL generated at dispatch |
| Kafka | Transactional producer/read_committed consumer; record ack, bounded retry and confirmed DLT; INDEXED/stale generations skip; FAILED is terminal |
| PDF parsing | Whole-file PP submit/poll/JSONL, strict malformed-page rejection, temporary-file cleanup; images prepared before atomic Text/Figure replacement and PARSED |
| Description | Model calls outside MySQL; per-Figure short save transaction checks current PARSED generation; committed descriptions reused on retry |
| Multimodal indexing | Text-only, Figure-only and mixed supported; no indexable artifacts fail; both Embedding sets must succeed before ES; stable IDs and Bulk item errors; INDEXED only after success |
| ES | TEXT/FIGURE metadata, long generation, public wire name, 2048-dimensional vectors; existing mappings only gain compatible missing fields |
| ACL/read APIs | COMPLETED FileUpload is the grant source; full owner/org/public union; current DB authorization used for retrieval/PDF/Figure/reference detail; private tags excluded from organization grants |
| Retrieval | Independent KNN and BM25 with the same ACL and current INDEXED-generation scope; post-search bulk recheck; 1-based RRF; per-page Text cap leaves Figure evidence intact; EMPTY/DEGRADED/FAILED distinct |
| RAG | Bounded answer-scoped context and stable evidence numbers; initial proactive retrieval; tools/summary share the same evidence registry; completion and MySQL history use the same mapping |
| Figure UI | Authenticated identity-based reads; image signature MIME; no-store; Blob URL revoke/abort; fresh access check on enlargement/focus; caption/metadata rendered as escaped text |
| Schema | deleted_at is official: entity + base DDL + rerunnable shared ACL migration; current tests use H2 explicitly and do not require incidental developer-MySQL columns |

A2/A3 recovery fixes preserve these properties. Read-time authorization remains
only as trustworthy as creation of FileUpload grants; A1 is explicitly accepted.

## C. Accepted limits — no architecture expansion requested

- Single-instance Outbox, at-least-once Kafka, checkpoint/idempotent external writes;
  no lease/inbox/exactly-once claim. Extreme consumer rebalance overlap may repeat
  parsing/model calls. Stable IDs protect ES duplication, not external model cost.
- No MinIO/MySQL/ES distributed transaction. Stable-path orphan images or cleanup
  leftovers can occur. New references can abandon old-generation orphans after a
  partial cleanup failure; current-generation filters hide them. Collection is deferred.
- Figure images use JPEG/PNG/WebP signature checking, not full image decoding.
  PP jobId is not durable; a failure before PARSED can submit the PDF again.
- New ACL grants are eventually visible in ES; revoked grants fail closed via DB
  checks. Already downloaded bytes cannot be revoked. Previously issued PDF signed
  URLs retain their existing expiry; Figure URLs themselves require fresh auth.
- Final authorization/generation rechecks reduce TOCTOU but cannot revoke bytes
  already being returned. Historical answer text remains in the owner's conversation;
  subsequent Figure/PDF reads must still pass current authorization.
- Mapping additions do not backfill historical documents or change existing
  analyzer/vector dimensions. Old docs without generation fail closed; deployment
  needs the documented migration and index rebuild/backfill. Content-level manual
  REINDEX is deferred; the dangerous legacy reindex is blocked for shared content.
- FAILED processing is terminal after DLT and needs deliberate operator recovery;
  a process crash can leave upload MERGING without an automatic recovery job.
- Large file scopes can hit Elasticsearch terms limits. Context uses a character
  budget, not exact model tokens. Citation validity/answer grounding is prompted,
  not automatically proved or rewritten.
- Complete modern non-PDF chunk artifacts are held in memory before atomic commit;
  larger-file memory optimization is deferred. Legacy incremental Tika persistence
  remains only in guarded compatibility entry points.
- LiteParse/Tika, PDFBox preview, legacy SearchResult and agent tools remain as
  compatibility paths. The legacy Playwright spec is excluded from application
  typecheck; its absent runner was not silently treated as a passed browser suite.

## Verification from the initial review

The initial review results below predate the focused A2/A3 follow-up.
Updated follow-up results will be recorded separately after validation. Tests are isolated/H2/loopback;
they do not replace real service acceptance of every failure interleaving.

- Backend compile: passed.
- Targeted backend review regressions: **106 passed**.
- Backend full suite: **925 passed** across 77 suites, zero failures/errors/skips.
  Previous ignored acceptance-probe XML reports without current test source were
  excluded from this count. This review adds 9 backend tests and 2 frontend tests.
- Frontend existing tests plus safety regressions: **30 passed**, zero failed.
- Frontend typecheck and production build: passed.
- git diff --check: passed (line-ending notices are not whitespace errors).
- Final tracked diff: **56 files, 1,392 insertions, 1,057 deletions**. This stat does
  not include the 70 untracked feature/test/doc files. An explicit candidate list
  is in ignored `target/final-review-commit-candidates.txt`: 122 paths, excluding
  four real acceptance artifacts and status-only files without an actual diff.
- Real chat page: connection refused on localhost:9527; backend and development
  dependencies were also stopped. They were not restarted for this review. Earlier
  live acceptance is documented separately; new renderer fixes were validated by
  Markdown rendering tests, not claimed to have received a new live-chat acceptance.

## Verification of the focused A2/A3 follow-up

- Backend compile: passed.
- Targeted parsing/Consumer/Outbox/upload/ACL/multimodal regressions: **168 passed**.
- Backend full suite: **935 passed** across 77 current-source suites; zero failures,
  errors or skips. Two old ignored probe reports without current source are excluded.
- This follow-up adds **10 backend tests**: 2 extraction-only tests, 4 atomic
  persistence/generation tests, and 4 cleanup/modern-Consumer pipeline tests.
  Existing Consumer assertions were adapted to the atomic non-PDF entry point.
- Existing frontend tests: **30 passed**; typecheck and production build passed.
  No frontend feature/source changes were made in this follow-up.
- git diff --check: passed. No schema/config/API Key changes were introduced.
- Race coverage uses real SQL transactions/locks and concurrent threads in isolated
  H2; external storage/index/PP/model calls are deterministic fakes. Real developer
  services were stopped, not restarted or claimed as a fresh live acceptance.
- A2/A3 are resolved. With A1 explicitly accepted by the owner, no other A-category
  blocker was found within this focused fix. Existing B items and C boundaries are
  retained. All prior uncommitted changes remain; no commit was created.

## Submission hygiene

Submit functional backend/frontend sources, their tests, ES mapping, sanitized
configuration examples, formal DDL/migrations and architectural/integration docs.
Keep tests that isolate the Spring context from the real MySQL schema. Keep this
report with the explicitly accepted limits if it is useful to the repository.

Do **not** submit `.env`, private keys/certificates, IDE/runtime folders, target,
build output, node_modules, logs, previous probe sources/reports or raw acceptance
data. Specifically exclude `docs/figure-chat-acceptance-results.json`: it is real
chat/retrieval evidence with actual document/user/conversation identities. The
acceptance Markdown files should only be submitted after checking/sanitizing those
identities; do not treat them as code/test fixtures automatically.

The four explicitly excluded real acceptance artifacts are:

- `docs/figure-chat-acceptance-results.json`
- `docs/figure-chat-acceptance.md`
- `docs/retrieval-development-acceptance.md`
- `docs/shared-content-acl-acceptance.md`

All changed/untracked candidate files were scanned without printing secrets.
Signature URLs found in candidate test sources use fake fixture hosts/tokens. No
real API Key, JWT or private key was found in those candidate changes. The local
env match in `.env.example` is the pre-existing deployment-key *file path*, not key
bytes. A tracked provider-config test contains a deliberately fake key fixture.
Old ignored target probe/log/JSON artifacts still exist and are explicitly outside
the commit scope. No temporary account, token or service deployment was created by
this review.
