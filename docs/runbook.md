# Operational runbook

Compose is for one-machine development. `docker compose up --build -d`, `docker compose logs -f api worker`,
`docker compose ps`. Health: http://localhost:8181/actuator/health/readiness. Metrics: /actuator/prometheus on private
admin network; do not expose management endpoints publicly. `docker compose down` preserves named volumes; never use
`down -v` unless destroying local data intentionally.

Inspect queue: SELECT state,count(*) FROM jobs GROUP BY state. A job running beyond90 s without heartbeat can be
reclaimed; each has at most3 attempts. Query processing_attempts for failures; error stores class only to avoid secrets.
Terminal failures require creator reprocess after root cause. A stale worker cannot win after another claim. On source
checksum mismatch request new upload; never bypass it.

Upload stuck after storage complete: retry complete with same upload ID, or wait expiry reconciler. Do not manually
recreate a multipart upload under same object key. Initiation orphan: S3 abort-incomplete lifecycle, then inventory
unreferenced sources with a safety delay. Database locks time out via infrastructure settings; provider operations are
bounded, but low-volume implementation holds row locks across storage operations. Monitor pool utilization before
increasing concurrency.

Disk pressure: drain worker, allow lease expiry, expand scratch; preserve sources. OOM: inspect cgroup events, reduce
profile parallelism or raise memory based on measurement. Worker shutdown stops heartbeats eventually; next owner
retries immutable prefix. PostgreSQL failover: stop admission until primary fenced/reachable, retry idempotent requests;
test managed service RPO/RTO. Outbox: processed marker and view increment share transaction; never reset marker
selectively unless rebuilding all aggregates.

Backup metadata with PITR/WAL, test restore monthly, inventory media and retain versioned objects. Disaster drill must
prove manifests and segments are available in standby before routing playback there. Single writer only during failover.
Migrate with expand/contract and deploy migrator Job before scaling; baseline Flyway starts in each instance with Flyway
lock. Large backfills must not run in startup migrations.

Proposed alerts: queued oldest age >5 min for10 min; failed attempts >5% for15 min; no successful claim with backlog
for2 min; database pool >90% for5 min; API5xx >1% for5 min; regional auth p99 >100 ms for10 min; disk free <20%; event
oldest unprocessed >60 s; origin5xx >1%. Thresholds need calibration. CDN/QoE/lag dashboards require collectors not
supplied by local Compose.

Production gates: OIDC issuer/audience configuration, no dev identity/default secrets, TLS/Secure cookie, private bucket
enforcement (LocalStack does not prove IAM), real-provider multipart fault tests, load/soak, multi-device browser
qualification, source demuxer hardening/scanning, audit/retention and physical erasure, orphan GC, backup/failover
drill, edge credential/revocation policy, capacity/cost quotas and security review. Kubernetes YAML has external
prerequisites and has not been applied to a cluster.

Kubernetes: build/tag the API/worker images from Dockerfile targets, create streaming-config Secret with
DB_URL/DB_USER/DB_PASSWORD/S3_ENDPOINT/S3_PUBLIC_ENDPOINT/S3_ACCESS_KEY/S3_SECRET_KEY/OIDC_ISSUER/AUTH_SECRET, then
apply helm/platform.yaml. This is plain Kubernetes YAML, not a Helm chart. Before applying, replace the origin proxy
Host storage:4566 with the deployed private S3 endpoint host, adjust DNS resolver for the cluster, supply TLS ingress
and restrict management/network access. Web ConfigMap embeds current client; regenerate it when client changes.

## Extension operations

DELETE now enqueues version-aware erasure; inspect erasure_jobs and owner erasure-status, not just videos.deleted. Do
not manually mark DONE when Object Lock/storage errors exist. Legal holds are moderator-only and serialize with
collection. Garbage collection protects current/active/session-referenced versions. Review [hardening](hardening.md) for
lock ordering, grace periods and retry semantics. Scanning/review must be enabled in production; default Compose
intentionally bypasses both. Use the [AWS runbook](../cloud/aws/README.md) for native role credentials, same-origin
CloudFront cookies, bounded revocation window and staged conformance checks. Cloud manifests must not be mistaken for a
deployed/certified cloud environment.

LocalStack restart durability failed in this environment even with PERSISTENCE=1. Preserve its volumes but do not treat
them as a reliable backup. The separate -p streaming-durable MinIO override supports restart testing and has its own
data volume/ports. Never attach old metadata to an empty replacement bucket. For application/DB restart chaos, poll an
actual metadata API query: the default readiness group does not establish database availability. Production should
deliberately configure readiness health-group dependencies and avoid putting external dependencies in liveness.

For scratch pressure/crash leftovers, use the [volume-aware retention runbook](scratch-retention-and-scanning.md), not
recursive deletion. Drain old workers before legacy adoption. Track primary erasure separately from scratch receipts.
Live scanner qualification now has a pinned image, bounded scan budget, signature reload/version check and benign
fixture runner; scanner-disabled development is still explicit. AWS/CloudFront and global-scale gates remain pending
staging access.

V9 adds durable scan_evidence. Activate large-file mode only after draining/upgrading API and workers together.
WINDOWED_MP4_V1 always requires manual moderation and loses original-file signature/archive semantics. Use
the [large-video operations guide](large-video-scanning.md); the new scanner qualification fixture has a test-only
canary rule, separate from the ordinary scanner image. Cloud/global certification stays pending staging.
