# Governance, erasure and reliability extension

## Identity and channels

GET /api/v1/users/me provisions/returns the OIDC-subject profile. PATCH requires If-Match profile_version
and {"displayName":"Name","preferences":{"language":"en","autoplay":true,"theme":"dark"}}. Subjects and roles come only
from verified tokens. This does not replace OIDC registration/password recovery. PUT /users/me/channel uses If-Match:-1
to create, then current channel version to update; handles are unique lowercase identifiers. GET /channels/{handle} and
/channels/{handle}/videos expose active creators and public, approved videos only. Channel listing is bounded100; long
catalogs still need cursor extension. POST /users/me/deactivate disables API actions, unpublishes creator videos and
expires viewer sessions. It is deactivation, not complete GDPR account erasure. A reactivation/admin identity-provider
integration is separately scoped.

## Moderation and scanning

Default processing requires ClamAV INSTREAM and manual moderation. A scanner timeout/error fails closed and retries with
bounded exponential backoff; a FOUND response quarantines the job as BLOCKED and rejects playback. Inputs accept a
bounded media-container whitelist (MOV/MP4, Matroska/WebM, AVI, TS, FLV, MPEG video), file-only protocols,
maximum7680×4320 and4 h; playlists/remote references are rejected. This reduces decoder attack surface but does not
replace sandboxing or media exploit testing.

`docker compose -f compose.yml -f docker/moderated.compose.yml up --build -d` enables ClamAV/manual review. Definitions
must download and scanner memory must be available. Scanner mode currently caps ingestion at100MiB and returns413 before
oversized upload reservation. Larger media needs a separately qualified scanner path. The default standard Compose
explicitly sets MEDIA_SCAN_MODE=disabled and MODERATION_AUTO_APPROVE=true for a fast local demo. DEV_BYPASS is stored in
attempt history; do not use either setting in production. Live ClamAV1.4.6 qualification now covers synthetic video,
EICAR, archived EICAR, ZIP expansion, byte limits and signature freshness;
see [current operations/evidence](scratch-retention-and-scanning.md). It does not certify an arbitrary malware/media
corpus or large files.

Reviewers require verified JWT claim roles:["MODERATOR"]. Dev identity `moderator` gets that role only with DEV_AUTH=
true. Configure the production IdP to emit trusted roles; no owner-supplied role fields are accepted. GET
/moderation/videos lists READY/PENDING rows. POST /videos/{id}/moderation-decisions requires If-Match current video
version and {"attempt":"current-attempt-uuid","decision":"APPROVED","reason":"Policy review"}. The decision is
append-only and tied to the exact media attempt and metadata version. Rejection unpublishes and blocks existing local
sessions; approval permits a separate creator publish. Metadata edits or reprocessing invalidate approval in normal
mode. Review uses row/version checks so a stale reviewer cannot approve replacement content. No automatic
semantic/CSAM/copyright classification or human-review service-level guarantee is claimed.

V2 preserves APPROVED on existing rows so old published content remains consistent during rollout; new videos default
PENDING. Existing content needs explicit rescan/review during a production migration. A zero-downtime mixed-version
deployment needs an API/worker drain or an approval-compatible intermediate release: old binaries do not implement new
governance/write fencing. The new columns are additive, but this semantic change must not be rolled out blindly to old
worker pods.

## Physical object erasure

DELETE /videos/{id} is idempotent for an owned tombstone. It locks video then jobs, cancels QUEUED/RUNNING work,
unpublishes immediately, records deleted_at, and creates durable erasure work. Worker object uploads now hold the job
fence through PUT and HEAD. Deletion waits for an already-entered write to complete, then stale/paused workers cannot
write after cancellation. Publication uses video-before-job lock order, consistent with deletion; heartbeats/claims only
lock jobs. Holding a fence during remote calls favors correctness over latency and can delay deletion acknowledgment
during a storage outage. Calls are bounded; a production high-volume implementation can use storage-enforced generation
fencing instead.

ERASURE_ENABLED defaults false; Compose enables it. Grace defaults86400 seconds and cannot be below1200 seconds. GET
/videos/{id}/erasure-status remains owner-readable after deletion and reports PENDING/DONE, deadline, attempts,
last_error, legalHold and tracked scratchPending. Primary-bucket DONE does not certify scratch erasure. A moderator can
PUT /videos/{id}/legal-hold {"held":true}; this locks the same video and prevents subsequent erasure. A hold cannot
resurrect bytes already erased.

Each pass deletes bounded pages of all S3 object versions and delete markers under the exact UUID prefix. Repeated
passes verify empty inventory before marking DONE. Storage errors retain pending work. Unfinished source multipart
uploads are aborted; quota is released once with the upload erased_at marker in the same transaction. Watch progress,
playback sessions, likes, comments and watchlist rows are removed; titles/descriptions/tags/current pointer are
scrubbed. Minimal tombstones, owner/accounting links, processing/audit metadata and backups remain. DONE means primary
configured bucket object inventory is empty, not a legal assertion about provider hardware, replicas, backups,
cached/downloaded copies or logs. Object Lock/replication/MFA-delete constraints can block permanent erasure and must be
certified per provider/retention policy.

GarbageCollector selects attempts older than7 days that are neither current, actively claimed, legally held nor
referenced by an unexpired playback session. It locks the video and job before collecting a bounded prefix and marks
collected_at only after an empty listing. Current versions are never collected. Object deletion can succeed before a
database rollback; retries use idempotent inventory/deletion. No API restores a collected old version. Unrecorded
multipart initiations rely on the configured two-day abort lifecycle; external/inventory-based orphan sweeps remain
necessary for manually uploaded or non-platform object keys.

## Failure tests

RecoveryIT uses real Testcontainers PostgreSQL and LocalStack versioning. Media encoding is deliberately mocked there to
make failure timing deterministic; smoke.py/browser-smoke.cjs use real FFmpeg and actual playback. Cases include
completion before DB rollback, concurrent completion,503 retry, all-version erasure with neighboring object preserved,
legal holds, erasure failure/replay/quota, deletion inside encoding, lease takeover and stale publication, metadata
failure followed by a winning retry and orphan GC, outbox rollback, unexpired-session protection, infected-source
quarantine, scanner outage/backoff and final expired lease diagnostics.

scripts/chaos.py kills a real FFmpeg worker with SIGKILL and stops real S3 emulator service in an isolated random
Compose project. It accelerates the expired-lease timestamp in SQL rather than waiting90 wall-clock seconds. It checks
recovery and refusal to acknowledge an unavailable source store, and always removes only its own disposable project.
Kubernetes/region/DB failover and resource-exhaustion chaos require staging; see verification-extension.md for executed
evidence.

Abort recovery now restores DRAFT and releases reservations exactly once, so a creator can start a fresh upload for the
same video. If the final source already exists after an ambiguous completion, abort refuses to uncharge it and directs
the client to retry completion. Old ABORTED sessions also receive seven-day source/version cleanup (with repeated
multipart abort), covering late/orphan provider objects without touching replacement uploads. V4 backfills erasure work
for legacy tombstones; V5 indexes abandoned-source selection.

The durable-local Compose override uses a pinned source build of MinIO because archived public image registries were
unavailable. It has a separate volume/project and local-only credentials; no existing LocalStack media is migrated or
destroyed. MinIO community is archived, so this is a local conformance fixture, not a recommendation for unsupported
public production deployment. Production uses a supported managed storage provider.

Moderators can POST /videos/{id}/review-playback-sessions to inspect pending private media without publishing it. The
role is checked at credential minting, sessions expire after15 minutes, local delivery is path-scoped and audited, and
progress on REVIEW sessions is ignored for watch history/view counts. Account deactivation/deletion still revokes local
requests. The web Review queue exposes preview/approve/reject; rejection can still be inspected by a privileged reviewer
if source scan was clean. Production IdP role changes require their token/credential revocation policy; already-issued
CDN credentials remain bounded by TTL.

The persistent local profile uses manual approval with an explicitly bypassed scanner. Creator uploads, signs in as the
dev moderator to preview/approve, then returns as creator to publish. Full scanner mode is a separate override.

Multipart capabilities now bind Content-Length and restrict part numbers to the reserved source size. The public
protocol uses fixed8 MiB parts except the last; clients already follow that layout. Signing an arbitrary number of
multi-GiB parts against a tiny reservation is no longer allowed. The live local-provider chaos test checks wrong-length
PUT denial; AWS conformance remains pending.

Production-mode API startup now fails if neither an OIDC issuer nor explicit development identity mode is configured.
This prevents accidentally accepting the known local HMAC secret in an otherwise production-configured API. Worker mode
denies control routes and does not need the signer secret.

MinIO fixture bootstrap configures versioning only: the provider rejected the AWS incomplete-multipart lifecycle action.
AWS Terraform retains that action. Local unrecorded multipart remnants require provider maintenance/inventory cleanup;
ordinary application sessions have expiry/abort/reconciliation.

Local scratch lifecycle, V7/V8 upgrade/drain protocol, scanner audit fields, failure evidence and reproducible commands
are defined in [scratch retention and scanning](scratch-retention-and-scanning.md).
