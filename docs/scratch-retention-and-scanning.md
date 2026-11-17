The explicit limited large-MP4 policy is now documented in [large-video-scanning.md](large-video-scanning.md); the
ordinary scanner policy below remains100MiB.

# Local scratch retention and scanner operations

This adds local filesystem recovery and a qualified small-file scanner path. Live AWS/CloudFront certification,
independent security review and global-scale validation remain staging gates.
See [executed evidence](verification-local-hardening.md).

## Scratch protocol

Each scratch filesystem has one persistent `.volume-id`. Each attempt records `scratch_volume`, `scratch_purged_at` and
a redacted `scratch_error`. The worker obtains an exclusive OS lock under `.locks/<attempt>.lock` before creating its
UUID directory and holds it through all source/output handles and subprocess cleanup. A JVM-local registry prevents
opening/closing overlapping file channels from accidentally releasing a POSIX lock. Never copy a volume marker onto
independently writable volumes. Shared storage must provide coherent POSIX locks; certify that behavior before using
network filesystems.

Normal terminal attempts are cleaned immediately unless held. Crash leftovers become eligible after **24 hours** by
default, or after the deleted video's erasure deadline. A bounded two-thread worker scheduler keeps maintenance
available during a long encode. The janitor runs every **60 seconds** when scheduled capacity is available, considers up
to100 attempts per pass and only collects the mounted volume. It skips legal holds, the current RUNNING attempt even
when its lease expired, and live OS locks. A frozen writer therefore remains protected after another worker takes over.
ProcessRunner kills/waits for children; a process that survives termination keeps its scratch lease quarantined until
exit. Container termination must kill its complete process namespace; unmanaged orphan processes and host-level copies
require separate investigation.

Cleanup locks video then job, rechecks authority, deletes without following symlinks, and records a receipt. Filesystem
deletion and PostgreSQL cannot share an atomic transaction: if the metadata commit fails after deletion, a later pass
observes the missing directory and safely completes the receipt. Database outage leaves files for retry. Unknown
directories are preserved. This intentionally trades prompt deletion for safety when ownership is uncertain. Removing
directory entries does not certify secure overwrite on SSDs, snapshots, backup media or RAM.

| Setting                      |        Default | Meaning                                              |
|------------------------------|---------------:|------------------------------------------------------|
| SCRATCH_CLEANUP_ENABLED      |           true | Background janitor; normal worker cleanup still runs |
| SCRATCH_RETENTION_SECONDS    |          86400 | Crash retention, minimum60 seconds                   |
| SCRATCH_CLEANUP_INTERVAL_MS  |          60000 | Background interval                                  |
| SCRATCH_ADOPT_LEGACY         |          false | Never enable with pre-V7 workers still running       |
| MEDIA_TEMP                   | /tmp/streaming | Persistent local scratch mount                       |
| MEDIA_FFMPEG_TIMEOUT_SECONDS |          18000 | Server-side rendition deadline, range1–18000         |

`streaming_scratch_pending` counts tracked attempts with outstanding receipts, including active/held attempts; it is not
an erasure SLA. `streaming_scratch_cleanup_errors` counts pending attempts with cleanup errors and has a15-minute
warning rule. Inspect attempt receipts, volume availability, `scratch_error`, legal holds and active writers before
intervention. A successful primary-bucket erasure `DONE` is separate from `scratchPending` on the owner status endpoint.
That number counts **tracked** scratch attempts; legacy/unknown directories, replicas, backups and CDN copies are
outside that count.

## Upgrade and legacy adoption

V7/V8 are additive migrations, but old workers do not hold scratch locks. Drain **every** old worker using the volume
before legacy adoption. A dedicated wrapper inspects mounts of all running local Docker containers and refuses adoption
if any still uses this volume. It only adopts database-known UUID directories whose attempt is no longer the current
RUNNING job; unknown files are preserved. Its scan is bounded to1000 root entries per pass. Inspect large/unknown/held
inventories separately if that bound prevents progress.

From the project directory, for the durable development stack:

```sh
# Package/build images first; no active uploads/jobs should be admitted during the upgrade.
mvn -DskipTests package
docker compose -p streaming-durable -f compose.yml -f docker/minio.compose.yml build api worker
docker compose -p streaming-durable -f compose.yml -f docker/minio.compose.yml stop worker
docker compose -p streaming-durable -f compose.yml -f docker/minio.compose.yml up -d --build --no-deps api
# Wait for API readiness and successful Flyway V8 migration before continuing.
python3 scripts/scratch-maintenance.py --project streaming-durable \
  -f compose.yml -f docker/minio.compose.yml --adopt-legacy
docker compose -p streaming-durable -f compose.yml -f docker/minio.compose.yml up -d --build --no-deps worker
```

Re-run the wrapper without `--adopt-legacy` for an ordinary maintenance pass; it safely coexists with new workers
through OS locking. Do not use recursive shell deletion, shorten retention to force removal, remove legal holds, or mark
another volume's receipts complete. For an inaccessible/destroyed volume, investigate storage/snapshot policy before
recording any external erasure evidence. Kubernetes emptyDir removal eliminates its live mount when the pod is removed
but is not certified disk sanitization.

## Qualified ClamAV policy

The pinned fixture uses [ClamAV1.4.6](https://github.com/Cisco-Talos/clamav/releases/tag/clamav-1.4.6), with its image
digest in `docker/clamav/Dockerfile`. It is an amd64 image and was emulated on the ARM64 development host. The daemon
has4GiB memory/2CPU,100MiB StreamMaxLength/MaxFileSize,400MiB MaxScanSize, AlertExceedsMax enabled and nonconcurrent
reload. [Official Docker guidance](https://docs.clamav.net/manual/Installing/Docker.html) explains the reload
memory/availability trade-off. Scans may retry during reload; no failed scan is accepted as CLEAN.

The application enforces a **100MiB upload/scan budget** with scanner mode enabled; oversized initiation returns413
before reservation or signed URLs. Scanner-disabled development retains the20GiB contract. A10GiB upload is **not
qualified** for this scanner. Changing an upload setting alone does not qualify a larger source: align API/worker policy
and scanner limits, then test the provider, corpus, resource budget and alternate scanner suitable for the required
size.

Before accepting a scan, the client verifies the running engine/database timestamp (maximum age7 days, future tolerance5
minutes), uses NUL-framed responses, bounds reply size, input bytes and socket/deadline behavior, and rejects incomplete
replies. Attempts audit engine, database version/build time and check timestamp. `INFECTED`, `LIMIT_EXCEEDED` and
`UNSCANNABLE_ARCHIVE` block publication. Transport/freshness failures retry under the existing three-attempt policy. A
CLEAN result is only evidence of this scanner/policy's checks; it is not a guarantee that a file is harmless.

Live qualification found that a compressed ZIP entry expanding beyond100MiB received CLEAN despite the daemon's
configured size alerts. The application now checks ZIP expansion before accepting daemon CLEAN: per-entry budget100MiB,
aggregate expanded budget400MiB, at most1000 entries, bounded nesting and deadline, streaming buffers, no extraction.
Nested trailing bytes are counted too. This is a ZIP guard, not an arbitrary-format sanitizer; malformed/adversarial
formats beyond the tested fixtures remain an audit/corpus gate. FFprobe's media-container whitelist still rejects
archives as media.

Clamd TCP is unauthenticated according to
the [official scanning documentation](https://docs.clamav.net/manual/Usage/Scanning.html). No host port is published;
use a restricted private worker/scanner network and production network policies. The Docker health ping alone does not
establish freshness. The qualification runner explicitly reloads and compares VERSION against the downloaded daily
database, since startup can load bundled definitions while Freshclam finishes an update.

## Reproduce local checks

```sh
mvn -Dtest=MediaTest,ProtectionTest,AuthenticationTest,ScannerPolicyTest,DatabaseIT,ApiIT,RecoveryIT,WorkerSecurityIT,MigrationIT -Dtest.excluded=none package
# Docker Compose >=2.24.4; builds run locally, no cloud account required.
docker compose build api worker
docker build --target api -t streaming-api .
docker build --target worker -t streaming-worker .
docker build -f docker/minio.Dockerfile -t streaming-minio:local .
python3 scripts/qualify-scanner.py --build --keep
LOCAL_LIVE_SCANNER=true MEDIA_SCRATCH_MIN_FREE_BYTES=268435456 python3 scripts/chaos.py
# This removes only the dedicated scanner fixture; chaos removes its random project's resources itself.
docker compose -f docker/scanner-qualification.compose.yml down --remove-orphans
```

Freshclam needs signature-update network access. The scanner runner waits up to five minutes for initial health and one
minute for the explicitly requested reload. EICAR fixtures are the benign standard AV test string, generated at runtime
alongside synthetic video/ZIP fixtures. The scripts save `target/scanner-qualification.json` and
`target/chaos-results.json`; failure logs/partial results are saved separately. CI includes these checks but has not
been executed on the hosted runner in this local session.

Chaos uses a random disposable Compose project, never a production endpoint. It freezes/kills workers, restarts
storage/PostgreSQL, injects a1MiB scratch tmpfs, enforces a96MiB cgroup limit to trigger OOM, and temporarily stops the
dedicated scanner. Lease/retention clocks are accelerated explicitly in SQL. The scanner fixture must not be shared with
interactive uploads while the outage test runs. These are repeatable local fault checks, not sustained load, managed
database failover or global availability validation.
