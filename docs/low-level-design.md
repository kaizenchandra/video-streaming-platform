# Low-level design

JDBC is used for explicit locks, SQL CAS and queue claims. DTOs are Java records. Domain identifiers are UUIDs,
identities are OIDC subjects. The modular packages use a small shared database facade; extracting repository ports into
standalone domains is appropriate as independent implementations emerge. This is pragmatic modular code, not full
framework-heavy DDD.

```mermaid
classDiagram
 Video "1" --> "many" UploadSession
 Video "1" --> "many" ProcessingJob
 ProcessingJob "1" --> "many" ProcessingAttempt
 ProcessingAttempt "1" --> "many" Rendition
 Video "1" --> "many" PlaybackSession
 PlaybackSession --> WatchProgress
 Video --> OutboxEvent
 class Video {
  UUID id
  String owner
  String visibility
  boolean published
  UUID currentAttempt
  long version
 }
 class UploadSession {
  UUID id
  long expectedBytes
  String sha256
  String multipartId
  String state
 }
 class ProcessingJob {
  UUID attempt
  Instant leaseUntil
  int attempts
 }
 class ProcessingAttempt {
  UUID id
  String immutablePrefix
 }
 class Rendition {
  int height
  int bitrate
  String manifest
 }
 class PlaybackSession {
  UUID id
  Instant expires
  long lastSequence
 }
 class WatchProgress {
  UUID session
  double position
  boolean completed
 }
 class OutboxEvent {
  UUID id
  int schemaVersion
  Instant processedAt
 }
```

```mermaid
stateDiagram-v2
 [*] --> DRAFT
 DRAFT --> UPLOADING: reserve / initiate
 UPLOADING --> QUEUED: complete / HEAD / job transaction
 QUEUED --> READY: leased attempt encodes and verifies
 QUEUED --> FAILED: retries exhausted
 FAILED --> UPLOADING: replacement source
 FAILED --> QUEUED: reprocess
 READY --> READY: reprocess creates new immutable version
 READY --> PUBLISHED: metadata flag
 PUBLISHED --> READY: unpublish
 PUBLISHED --> DELETED: soft delete
```

Processing detail stays on job/attempt rows rather than overloading video state: QUEUED/RUNNING/SUCCEEDED/FAILED.
Validation, encoding, packaging and verification occur within an attempt. Only one active job per video is permitted by
a partial unique index. One authoritative current_attempt references the winning version. Version increments on metadata
and successful processing commits.

```mermaid
sequenceDiagram
 participant C as Creator
 participant A as API
 participant S as S3
 participant D as PostgreSQL
 participant W as Worker
 C->>A: initiate (idempotency key / checksum / size)
 A->>D: lock account, reserve quota, upload session
 A->>S: initiate multipart
 C->>S: PUT signed parts directly
 C->>A: complete
 A->>D: lock upload
 A->>S: list authoritative parts, complete, HEAD
 A->>D: commit quota, source, job, event
 W->>D: SKIP LOCKED claim + attempt
 W->>S: download / checksum / decode
 W->>S: upload and verify all artifacts
 W->>D: fenced current_attempt commit
 C->>A: publish with If-Match
```

```mermaid
sequenceDiagram
 participant V as Viewer
 participant A as API
 participant N as Nginx
 participant S as S3
 V->>A: POST playback-sessions
 A-->>V: scoped HttpOnly cookie + master URL
 V->>N: GET master / segment
 N->>A: authorize cookie + exact path
 A-->>N: private upstream presigned URL
 N->>S: signed GET
 S-->>V: media via Nginx
```

Repositories: Db.one/video/own/readable are shared JDBC adapters; Storage encapsulates AWS SDK. Application services use
TransactionTemplate or @Transactional. Error contract is Problem Details; invalid payload 400, authentication 401,
missing/hidden 404, owner 403, invalid transition/stale version 409. Database uniqueness conflicts map to 409. OIDC
subject rather than user-provided owner fields governs all writes.

Watch progress uses a strictly increasing sequence per session. Duplicate/old sequence is ignored. Newest-created
session wins across devices; seeking backward is allowed within that session. The completed field is derived from >=95%
position. Client sends every 15 s, so last seconds can be lost on browser close. Qualified view requires both elapsed
wall time and reported position >=min(30 s,80% duration), once per session, then async transactional aggregation. This
is a transparent baseline, not fraud-resistant unique-human measurement.

Retention proposal: playback sessions 24 h after expiry, raw events 30 days, progress 365 days inactive, unreferenced
attempts 7 days, deleted sources after legal hold. Retention/physical deletion automation remains a production gate.
Partition raw events by month when measured volume warrants; indexes bound live queue and catalog access. Migrations use
expand/backfill/contract; do not remove old columns during mixed-version rolling deployment.

The governance/retention extension adds channels, moderation_decisions, erasure_jobs and playback session purpose
(WATCH/REVIEW). See migrationsV2–V6 and hardening.md. REVIEW sessions are scoped/audited and excluded from progress/view
aggregation.
