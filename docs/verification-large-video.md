# Executed large-video and adversarial-media verification — 2026-11-15

AWS/CloudFront certification and global-scale testing remain **pending staging access**. No AWS/provider certification,
cloud deployment, global workload, independent malware/security certification or new API-throughput/QoE benchmark was
run. This report qualifies a specific local workflow under an explicitly limited windowed policy; it does not certify
native whole-file malware semantics above ClamAV's file limit.

## Automated/build evidence

**84 tests passed; zero failures, errors or skips.** [Suite result](test-results-large-video.json). Java21.0.13, Spring
Boot3.5.16, PostgreSQL17/LocalStack Testcontainers. The command executed with JDK21 and the local OrbStack Docker socket
was:

```sh
mvn -o -Daether.syncContext.named.factory=noop -Dtest=MediaTest,ProtectionTest,AuthenticationTest,ScannerPolicyTest,LargeScannerTest,WindowPolicyTest,DatabaseIT,ApiIT,RecoveryIT,WorkerSecurityIT,MigrationIT -Dtest.excluded=none package
```

Coverage adds full byte-window/hash accounting, overlap-boundary detection with a fake protocol peer, later-window
disconnect, a nonresetting source deadline, unsupported/archive/external-reference/overflow/chunk-offset gates, distinct
verdict acceptance, incomplete/hash-mismatched evidence rejection and role-restricted moderator disclosure. Existing
ownership, quota/idempotency, concurrency, retries, publication/GC fencing, scratch retention, process and security
regressions passed. Fresh and upgrade migrations reachedV9.

Final API/worker/verify Docker images built. Python compilation, JavaScript syntax and Compose configuration checks
passed. CI was extended with the actual decoder corpus and a small windowed-flow variant; hosted GitHub CI was not
executed in this session.

## Actual10GiB source and complete workflow

[Full machine-readable result](large-video-qualification.json). Generated **10,749,547,065 bytes** of actual
synthetic3840×2160 v210 MOV video:486 frames at24fps,20.25 seconds. It has no sparse or filler padding. This
deliberately exercises object size,64-bit container offsets, multipart pagination, streaming memory and4K decoding; it
does not represent a long compressed film's quality/complexity distribution.

The qualified run passed:

* Engine freshness/version checks with live ClamAV1.4.6/database28183. The dedicated test image has an explicitly
  labeled coverage-canary rule; its digest is retained in the report.
* Any-offset test-canary detection across64MiB, beyond3GiB, and near the end of the media payload. Each mutation was
  restored and full-file SHA-256 matched before upload.
* Creator reservation and stable initiation replay. Direct8MiB multipart upload used **1,282 parts**, exercising
  provider ListParts pagination beyond1,000 entries.
* Saved checkpoint after part3, reopened source, resumed at4 and replayed the last acknowledged part with the same ETag.
* Two concurrent completion requests returned durable COMPLETED, created exactly one job and charged quota exactly once.
* A worker read/hashed/scanned the complete source with **161 windows**,64MiB per window/64KiB overlap, plus bounded
  complete metadata. Covered bytes equaled source bytes and the coverage hash equaled the upload hash. It stored
  WINDOWED_MP4_V1 and durable V9 evidence.
* Despite deliberately configuring worker auto-approval=true in the disposable profile, the windowed result remained
  PENDING/unpublished. Early publication and owner self-approval failed; trusted manual review/approval then allowed
  creator publication.
* Authorized delivery fetched all **30 segments across five HLS variants** (240p,360p,480p,720p,1080p). Anonymous access
  was denied.
* Normal processing scratch had a purge receipt. Deletion immediately revoked local playback credentials. The random
  project's MinIO/PostgreSQL/scratch volumes were subsequently removed; no production erasure inference is made.

A retained copy of the generated HLS output was independently decoded with FFprobe in network-disabled512MiB/2CPU
containers. Every rendition decoded all **486 H.264 frames** at its expected
dimensions. [Decode evidence](large-video-decode.json). This copy came from the synthetic worker output; the large run's
origin responses were fetched separately. The final small CI variant additionally downloads and decodes the actual
authenticated origin responses, described below. No new browser matrix, A/V synchronization, perceptual quality or
bandwidth-switch/QoE certification was run. The large source contains video only;4K/HDR output is not claimed (the
current ladder ends at1080p).

## Measured local timings and resource boundaries

| Measurement                                                           |                        Observed |
|-----------------------------------------------------------------------|--------------------------------:|
| Large multipart upload phase                                          |                  38.632 seconds |
| Worker startup, source download/hash, scan, transcode and publication |                 231.396 seconds |
| Coverage-canary detection sequence, including reading up to the tail  |                 154.345 seconds |
| Full successful qualification script                                  |                 450.357 seconds |
| Python client's recorded peak RSS                                     | 34,766,848 bytes (about33.2MiB) |

The source was streamed under a192MiB Java heap /512MiB container in direct detection qualification. The pipeline worker
had2CPU/2GiB; the scanner had2CPU/4GiB and used the amd64 image emulated on an ARM64 shared developer host. The client
used bounded8MiB buffers and the scanner64KiB buffers. These are one-run local measurements with warm/cached components,
local MinIO and no TLS/CDN; do not extrapolate them into production upload/scan/transcode capacity or latency SLOs. The
aggregate worker time is not pure encoding time.

## Real adversarial decoder corpus

[Corpus result](adversarial-media.json): a valid H.264 control produced two renditions. The actual FFprobe/FFmpeg path
rejected truncated MP4, audio-only source, oversized dimensions,240fps input,17 streams, corrupted NAL length, remote
HLS and local concat references. The final corrupt-packet fixture targets a later packet; an explicit probe returned
valid metadata JSON before strict decoding failed with IOException. No rejected case produced a master manifest. A
loopback network canary saw **zero connections**. Processes ran with512MiB/2CPU and bounded timeouts; fixtures were
generated locally and removed.

These are synthetic negative checks, not an exploit/malware corpus or proof of sandbox escape resistance. Earlier
scanner ZIP-expansion, truncated-response, freshness and timeout tests remain covered separately; the structural
large-file gate rejects unsupported large containers.

## Important failed assumption, preserved

The first raw-pixel boundary test using native EICAR **failed detection**. The script failed before any
upload/publication, and restored the fixture. [Retained finding](large-scan-initial-failure.json). It had assumed native
EICAR would act as an arbitrary embedded byte pattern. The corrected coverage test uses the clearly labeled
qualification-only `Streamworks.Coverage.Canary` any-offset rule; it is not evidence that native EICAR or every
production signature matches arbitrary embedded bytes. Standard68-byte EICAR and ZIP-contained EICAR continued to pass
using native signatures. [Baseline report](scanner-large-video-baseline.json).

Windowed submission loses original whole-object hash/offset/archive/logical-signature semantics regardless of successful
canaries. The result stays distinct from CLEAN, requires opt-in and manual review, and remains unsuitable as a claim of
complete original-file malware certification. [Policy and exact operational limits](large-video-scanning.md).

## Final-code CI variant, chaos and rollout

The final images passed `MEDIA_SCRATCH_MIN_FREE_BYTES=268435456 python3 scripts/qualify-large-video.py --frames 6`
in26.114 seconds. [Small result](large-video-small-qualification.json). It uses a roughly126MiB4K source, exercises the
windowed branch, manual approval, quota, resume/completion, delivery and revocation, then decodes every **fetched** HLS
rendition offline and verifies six frames each. Its report explicitly makes no10GB claim. The full-size and small
results are stored separately.

The updated worker/API passed the entire real chaos script with live scanner in **143.361
seconds**. [Chaos result](chaos-large-video.json). Executed cases include SIGKILL/reclaim, cgroup freeze with stale OS
lock and legal hold, real ENOSPC, cgroup OOM/recovery, process hang/deadline cleanup, object-store/DB shutdown/restart,
all-version primary erasure, native EICAR quarantine and scanner outage/recovery with audit fields. Lease/retention
clocks were explicitly accelerated. Local primary restart is not managed failover; no regional fault or full30-scenario
certification is implied.

The preserved development stacks were checked idle before their coordinated V9 rollout, preserving
databases/storage/scratch and their explicit synthetic-development scanning bypass. The limited policy is available
through a separate opt-in moderated profile. [V9 rollout receipts](large-video-local-upgrade.json) and
the [post-rollout upload/playback smoke result](large-video-rollout-smoke.json) passed; the live scanner test fixture
and disposable qualification resources were stopped/removed after testing. No unrelated application resources were
removed.

## Remaining gates

Original-file malware semantics for large objects, a broad codec/adversarial/embedded/archive corpus, security
isolation/egress/network-filesystem qualification, long compressed4K/HDR/audio workloads, independent provider/security
review, real AWS/CloudFront behavior, managed failover and distributed global/QoE validation remain unverified. The10GiB
result establishes the implemented local workflow and bounded byte-submission policy only. Cloud/global work
intentionally remains pending staging access.
