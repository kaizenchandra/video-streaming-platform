Current large-video evidence: [verification-large-video.md](verification-large-video.md). This earlier report remains
historical.

Latest scratch/scanner/chaos evidence: [local hardening verification](verification-local-hardening.md). Older reports
below remain historical.

Current extension evidence: [verification-extension.md](verification-extension.md). The report below records the
original baseline only.

# Executed verification — 2026-11-14

This report records local evidence, not production certification. Java21.0.13, Spring Boot3.5.16, macOS ARM64 host,
OrbStack Docker29.8.2. API container limit2 CPU/768MiB; worker2 CPU/2GiB. PostgreSQL17-alpine, LocalStack4.10.0 and
Nginx1.28-alpine. Local storage/CDN/IAM behavior is not equivalent to AWS/CDN production.

## Passed checks

* Java21 Maven package: compiled and packaged runnable JAR; seven media/unit tests passed, zero skipped.
* Testcontainers: two database tests passed (fresh Flyway migration, publication check constraint,20 concurrent like
  inserts yielding one row).
* Spring Boot/MockMvc with real PostgreSQL: six API tests passed (bearer required, private metadata hidden/owner
  mutation enforced, cannot publish unprocessed source, optimistic version rejection, absent media cookie denied,
  request validation).
* Multi-stage Docker Compose build succeeded for API and FFmpeg worker, and the exact Compose stack ran.
* Synthetic8-second640×36024fps H.264/AAC source:781,521 bytes; SHA-256 direct multipart upload; concurrent completion
  both returned COMPLETED; generated240p and360p HLS outputs and cover; fetched all master/variant/segment objects;
  denied anonymous access; duplicate/out-of-order progress ignored; history, likes, comments and async qualified-view
  count worked. Duplicate qualified progress yielded exactly one view. Soft deletion revoked the existing cookie on the
  next request. See media-regression.json.
* Google Chrome through Playwright: actual HLS decoded playback advanced beyond2 seconds, two variants plus auto
  appeared, seeking to5 seconds continued playback. See browser-regression.json. No claim of browser matrix or automatic
  bandwidth-switch certification.
* Readiness endpoint returned UP and Prometheus exposed request/JVM/pool metrics plus streaming_jobs_queued and
  streaming_uploads_active.
* Python compile checks and JavaScript syntax checks passed.

Observed final worker attempt durations for the small8-second fixture were0.865,0.695 and0.626 seconds in container
logs. These include download/checksum/encode/upload/HEAD/metadata, exclude queue waiting, and are not representative
of4K or large videos. No A/V-sync or perceptual-quality benchmark was executed.

## Small local metadata measurement

Warm catalog GET /api/v1/videos via Nginx, authenticated, tiny dataset,10 warmup requests,200 measured requests,
concurrency8 using Python threads. Total0.304 seconds, achieved658.6 requests/s; p50=6.43 ms, p95=50.16 ms, p99=67.36
ms. See metadata-measurement.json and scripts/measure.py. This very short run is not a sustained capacity estimate and
does not measure playback authorization SLA or CDN throughput. No1000/10000/100000 RPS or million-viewer target was
certified.

## Defects found and corrected

Initial startup caught a JWT/media encoder bean-name collision; bean renamed. Process-tree inspection denied by macOS
sandbox initially masked subprocess errors; cleanup now preserves original failure and always kills the direct child.
First Compose media run caught nonroot scratch-volume ownership; Dockerfiles now initialize writable scratch, and the
existing test volume was repaired before rerunning. Final container and browser regressions passed after these fixes.

## Not executed

All30 failure scenarios are analyzed in failure-matrix.md, but full chaos coverage is not implemented or executed.
Unexecuted: ambiguous provider responses,10GiB/4K upload, worker crash/lease races, memory/disk faults, DB failover,
regional outage, real AWS/MinIO IAM certification, CDN behavior, Kubernetes apply, long soak/load, Safari/Firefox/mobile
qualification and media corruption corpus. Load scripts and deployment manifests are provided; production gates remain
in runbook.md and limitations.md.
