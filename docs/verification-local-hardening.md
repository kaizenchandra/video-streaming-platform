Current large-video evidence: [verification-large-video.md](verification-large-video.md). This earlier report remains
historical.

# Local retention, scanner and chaos verification — 2026-11-14

This is the current evidence for the requested local continuation. AWS/CloudFront certification, independent
provider/security audit and global-scale testing remain **pending staging access**. No cloud deployment, provider
certification or global load was run. Prior measurements in the older verification reports remain historical; no new
throughput/QoE benchmark was measured here.

## Build and automated checks

**70 tests passed; zero failures, errors or skipped tests.** Java21.0.13 / Spring Boot3.5.16. Suite: MediaTest7,
ProtectionTest9, AuthenticationTest2, ScannerPolicyTest7, DatabaseIT2, ApiIT12, RecoveryIT28, WorkerSecurityIT2,
MigrationIT1. [Machine-readable result](test-results-local-hardening.json).

```sh
mvn -o -Daether.syncContext.named.factory=noop -Dtest=MediaTest,ProtectionTest,AuthenticationTest,ScannerPolicyTest,DatabaseIT,ApiIT,RecoveryIT,WorkerSecurityIT,MigrationIT -Dtest.excluded=none package
```

JDK21 and the OrbStack Docker socket were selected in the local environment. Offline/noop options use existing cached
dependencies; they are not required normally. The packaged application and multi-stage API/worker/verify images built
successfully. PostgreSQL Testcontainers applied fresh V1–V8 migrations and the existing migration regression passed.
Python compilation and Compose config checks passed. Hosted CI was updated to run live scanner qualification and the
expanded fault suite, but was not executed on GitHub in this session.

New coverage includes volume identity, retention, active-current protection even after lease expiry, old-writer OS
locking, legal hold, deleted-video deadline, cross-volume isolation, symlink/unknown-path safety, filesystem deletion
followed by DB rollback, opt-in legacy adoption, surviving-child quarantine, and a separate-JVM POSIX lock probe.
Scanner tests cover freshness/clock policy, input budget, truncated responses, timeouts, heuristic-limit classification,
compressed entries and nested trailing bytes. Oversized scanner-enabled upload initiation returns413.

The worker now uses two bounded scheduling threads, so the fixed-delay encoder does not monopolize the maintenance
scheduler. The final build, suite and chaos rerun include that change. This does not make encoding unbounded: the same
worker poll does not overlap itself and still runs one job at a time.

## Actual live scanner

[Report](scanner-qualification.json): pinned ClamAV1.4.6, amd64 image emulated on the ARM64 host,4GiB/2CPU container.
Freshclam downloaded daily database **28183**, built **2026-11-14T06:24:17Z**; qualification explicitly reloaded and
confirmed the daemon VERSION matched the downloaded database. Runtime freshness policy remains maximum7 days
with5-minute future tolerance.

Executed `python3 scripts/qualify-scanner.py --build --keep`:

| Fixture/check                                      | Observed                                    |
|----------------------------------------------------|---------------------------------------------|
| Synthetic3-second H.264 video                      | CLEAN                                       |
| Standard benign EICAR AV test fixture              | INFECTED                                    |
| EICAR inside ZIP                                   | INFECTED                                    |
| ZIP entry expanding to101MiB                       | LIMIT_EXCEEDED by application ZIP preflight |
| 2KiB source against a1KiB configured client budget | LIMIT_EXCEEDED before streaming             |
| Database age beyond configured limit               | Rejected with simulated clock               |
| Explicit daemon reload vs database on disk         | Matching version28183 confirmed             |

Qualification elapsed9.101 seconds including the requested reload wait. This is not scan throughput or a general
malware-detection benchmark. The configured scanner-enabled ingestion budget is100MiB; the raw live byte-budget case
uses a smaller configured threshold, not a complete100MiB boundary/video corpus. The real chaos transcode fixture is a
roughly24MB30-second720p source. No10GiB/4K scan, arbitrary archive corpus, exploit resistance or independent scanner
security certification is claimed.

The initial live expansion test **failed**: ClamAV returned CLEAN for the101MiB expanded entry despite configured size
alerts. The correction is a bounded streaming ZIP expansion guard, including nested trailing-byte accounting; it does
not establish safety for every format. Startup also demonstrated that downloaded definitions can differ from those
loaded by the daemon. A reload initially caused a VERSION timeout under nonconcurrent loading; the qualification harness
now waits with a one-minute bound. Runtime scan failures still
reject/retry. [Retained findings](local-hardening-findings.json).

## Real container/process faults

The final `LOCAL_LIVE_SCANNER=true MEDIA_SCRATCH_MIN_FREE_BYTES=268435456 python3 scripts/chaos.py` passed in **111.079
seconds**. It used a random isolated Compose project, the durable local MinIO fixture, actual FFmpeg, real filesystem
locks and the private live scanner. [Report](chaos-local-hardening.json).

* Signed part length mismatch rejected; legitimate direct upload succeeds.
* SIGKILL during processing; another attempt recovers to READY without a partial current pointer.
* Crashed-attempt scratch is collected with a receipt when its retention clock is advanced.
* Object-store outage returns503 without false upload completion; restart preserves objects.
* PostgreSQL shutdown/restart returns503, recovers durable metadata and leaves media playable.
* Versioned primary-bucket erasure independently verifies both video/source prefixes empty.
* Docker cgroup freeze leaves a stale writer's OS lock alive; another worker wins the expired job while maintenance
  skips the frozen writer. After termination, legal hold still prevents collection; releasing the hold permits a
  receipt.
* Hanging encoder hits a2-second process deadline, leaves no READY pointer and no surviving sleep child.
* A1MiB tmpfs causes actual ENOSPC during download; job records SCRATCH_FULL and never publishes.
* Reducing a worker to96MiB causes Docker OOMKilled; lease takeover with the normal limit recovers to READY.
* Live EICAR is quarantined before encoding/publication.
* Real scanner shutdown fails closed; restart/retry reaches READY and stores CLEAN plus engine/database/build-time audit
  evidence.

Lease, retention and erasure clocks were deliberately accelerated in SQL; no24-hour wall-clock soak or90-second expiry
timing certification is implied. A single local primary restart is not managed failover. The expanded suite first
exposed fixture issues (host-port reassignment and ineffective in-namespace PID1 stop); those were corrected and the
entire suite rerun. The final resources were removed only for its random project; unrelated application stacks were
preserved.

## Preserved local deployments and media regression

Both existing streaming stacks were checked idle before draining their workers. Their scratch directories were already
empty. API migrations reachedV8, the safe standalone maintenance/adoption wrapper ran with zero eligible directories,
and workers restarted on the final images. The wrapper was also executed with a running volume user and correctly
refused adoption. [Upgrade receipts](local-upgrade.json). No database, storage volume, hold or retention deadline was
reset to force deletion.

`BASE=http://localhost:8091 python3 scripts/smoke.py` then passed on the final durable stack: direct multipart upload,
duplicate completion, actual FFmpeg HLS240p/360p, moderator preview/approval, publication, all segment fetches,
unauthorized denial, deduplicated progress/view counting, engagement and deletion
revocation. [Media result](media-local-hardening.json). The first invocation immediately after recreation encountered
startup502; the successful rerun followed API readiness. No new browser matrix or visual/QoE certification was run.
These preserved demo stacks still explicitly bypass scanning for synthetic development; the isolated scanner/chaos tests
exercise real scanning.

Readiness and the scratch pending/cleanup-error Prometheus gauges were checked after rollout; both APIs were UP with
both gauges zero. The normal synthetic attempt had a purge receipt while primary-bucket erasure remained PENDING under
its unchanged retention deadline. See [runtime evidence](local-runtime-health.json). Primary-bucket DONE remains
distinct from tracked scratchPending and cannot prove replica, backup, CDN, downloaded-copy or physical disk
sanitization. The dedicated scanner test fixture was stopped after qualification/chaos; its reproducible launcher
remains available.

## Operational scope and remaining gates

[Runbook and exact reproduction commands](scratch-retention-and-scanning.md) explain the file-lock protocol, per-volume
receipts,24-hour crash retention, legal-hold serialization, drained legacy adoption, API413 behavior and scanner limits.
Implementations are in `processing/ScratchSpace`, `ScratchMaintenance`, `WorkerScheduling`, `MediaScanner`,
`ScannerQualification`, V7/V8 migrations, and the corresponding scripts/Compose/CI configuration.

Remaining: supported large-source scanner integration and adversarial corpus/resource testing, network filesystem lock
qualification, unknown/external/removed-volume scratch inventory, secure storage sanitization and account-wide
backup/replica erasure, managed failover/regional chaos, production network/identity/IAM/secret audit, real
AWS/CloudFront certification and distributed scale/QoE validation. The cloud/global gates intentionally remain pending
staging access.
