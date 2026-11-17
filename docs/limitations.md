Current large-file policy and qualification limits: [large-video-scanning.md](large-video-scanning.md)
and [verification-large-video.md](verification-large-video.md).

# Current limitations

The governance/erasure/cloud extension supersedes the original baseline descriptions below.
See [hardening](hardening.md) and [current local evidence](verification-local-hardening.md).

Implemented since the initial baseline: profiles/preferences, unique channels, deactivation, moderation
role/queue/decisions with stale-review rejection, fail-closed ClamAV integration, cancelled-job fencing, retry backoff,
terminal lease diagnostics, S3 version/delete-marker erasure, legal holds, quota release, reference-aware orphan-attempt
GC, native AWS credentials, CloudFront cookie signer/Terraform and opt-in provider/CDN certification tooling.

Still unverified or incomplete: real AWS and live CloudFront certification, original whole-file
signature/embedded-archive semantics for large sources and a broad adversarial corpus, independent security audit,
global distributed load/QoE, regional/DB failover, adversarial resource-exhaustion corpus, account-wide privacy
erasure/backups, automated semantic moderation, seamless CDN credential renewal and immediate per-video edge revocation.
Replica/object-lock/CDN erasure follows provider policy and cannot be inferred from primary-bucket DONE. Legacy pre-V2
content inherits approval and requires an explicit migration review. External/manual orphan keys need inventory sweeps
beyond attempt/reference collection.

Local scratch retention and live ClamAV small-fixture qualification are implemented and tested; legacy adoption requires
drained old workers. Receipts cover one mounted filesystem, not SSD sanitization/backups. ZIP expansion limits
supplement scanner alerts but do not certify every archive/media format.
See [operational scope](scratch-retention-and-scanning.md).

## Original baseline snapshot (historical)

Working baseline: metadata/ownership/visibility, source multipart upload with durable sessions, quota/rate limit,
SHA-256 verification, PostgreSQL durable queue/outbox, independent workers, retry/fencing, HLS ladder and thumbnail,
authorized Nginx delivery, web client, search, progress/resume, likes/comments/watchlist add,
category-affinity/popularity/freshness ranking, health/Prometheus and deployment artifacts.

Limits: global throughput is unmeasured; local per-segment database authorization is deliberately strict and expensive.
LocalStack emulates S3 and is not production IAM validation. Source limit20 GiB, browser checksum demo256 MiB; python
uploader has bounded memory. Sequential H.264 renditions up to1080p, no HDR/DRM/DASH/captions. Probe/playlist validation
is not perceptual/A-V-sync certification. Worker free-disk check is not per-job disk accounting. Kafka, Redis and full
ML stack intentionally omitted.

Profiles use identity subjects rather than full channels/preferences/account-management UI. Catalog sorted by creation
date; trending currently shares public catalog ordering rather than a time-window trend model. Recommendation score uses
log views, reciprocal age, category affinity; not normalized across cohorts and no diversity/abuse correction. Search
has PostgreSQL FTS, category/creator/max-duration filters; no typo/autocomplete/multilingual or explicit relevance
sorting. Some collection routes use bounded lists rather than full cursor APIs. Metadata PATCH is a validated
replacement of editable fields with optimistic If-Match, not JSON Merge Patch. Engagement operations are idempotent for
likes but comments lack idempotency keys. Client upload sends parts sequentially; external clients may upload signed
parts in parallel. Cookies require playback restart after expiry; no seamless renewal.

Events use schema version, ID, aggregate and timestamp but lack rich aggregate-version/correlation envelopes. All local
events are marked handled by the database consumer; external Kafka delivery is not implemented. No heartbeat ingestion
throttle server-side, comprehensive client QoE export, bot-proof view counting, OTel collector deployment, sophisticated
retry backoff, automatic artifact garbage collection/physical erasure, or studio entitlements. Lease exhaustion
reconciles job terminal state; some video/attempt diagnostic states may remain QUEUED/RUNNING and should be derived from
jobs until improved. See failure matrix for proposed tests versus executed evidence.

LocalStack persistence failure was reproduced during restart chaos; its ordinary Compose mode remains an ephemeral
emulator. Use the separate durable MinIO fixture for restart tests. This does not certify cloud IAM, provider
replication, Object Lock, encryption deployment, availability or supportability.
