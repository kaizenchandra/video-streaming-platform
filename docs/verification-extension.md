Current large-video evidence: [verification-large-video.md](verification-large-video.md). This earlier report remains
historical.

Latest scratch/scanner/chaos evidence: [local hardening verification](verification-local-hardening.md). Older reports
below remain historical.

# Extension verification — 2026-11-14

This supersedes the original baseline report for the newly implemented scope. All numbers below come from executed local
checks. No AWS account, staging domain or cloud traffic/cost ceiling was supplied; live cloud certification and
global-scale validation remain unexecuted.

## Automated suite

**51 tests passed, zero failures/errors/skips** in the final release suite:7 MediaTest,9 ProtectionTest,2
AuthenticationTest,2 DatabaseIT,11 ApiIT,17 RecoveryIT,2 WorkerSecurityIT,1 MigrationIT.
See [machine-readable results](test-results-extension.json). Java21, Spring Boot3.5.16, AWS SDK2.27.21 including STS,
PostgreSQL17 Testcontainers and LocalStack4.10.0. The recovery tests use real DB/S3 APIs but mocked encoding to control
failure timing. Other scripts use actual FFmpeg and a browser.

Executed command from project root (JAVA_HOME selected JDK21 and DOCKER_HOST pointed at OrbStack):

```sh
mvn -o -Daether.syncContext.named.factory=noop '-Dtest=MediaTest,ProtectionTest,AuthenticationTest,DatabaseIT,ApiIT,RecoveryIT,WorkerSecurityIT,MigrationIT' -Dtest.excluded=none package
```

Coverage includes identity/channel CAS, unique handles, deactivation, role-restricted moderation and stale reviews,
private reviewer previews without watch/view aggregation, fail-closed issuer configuration and audience checks,
multipart reserved-part bounds and signed Content-Length, source-complete/DB rollback recovery, concurrent completion,
storage503 retry, abort/restart, late aborted-source GC, legal holds, all-version/delete-marker erasure, quota replay,
active-session GC protection, stale worker fencing, failed publication/retry, scanner infection/outage, retry backoff,
exhausted leases, outbox rollback and V1-to-current migration of publications/tombstones.

## Real process/storage chaos

The final `python3 scripts/chaos.py` run used a separate random Compose project and a **persistent source-built MinIO**
fixture. It passed in44.252 seconds:

* Wrong-length signed upload part rejected; correct parts accepted.
* Real worker SIGKILL during a30-second720p transcode; replacement attempt recovered and reached READY.
* Real object-store shutdown: completion returned503 and did not falsely acknowledge completion.
* Real PostgreSQL shutdown/restart: API returned503 during outage and subsequently recovered durable metadata.
* Media remained playable after both store and database restarts.
* Deletion canceled outstanding ownership, erased all versions/markers under video/source prefixes, and standalone SDK
  inventory verification found both prefixes empty.

Lease expiry and erasure retention clocks were explicitly accelerated in SQL. This does not test90 seconds of heartbeat
expiry or24 hours of retention in wall-clock time. Database restart was a local single-primary restart, not managed
multi-AZ failover. See [chaos report](chaos-results.json). Cleanup removed only the random disposable project's
resources; separate review volumes were preserved.

A previous LocalStack restart run **failed** media persistence: PostgreSQL still referenced READY assets but the
emulator no longer served them. That failure is retained as a limitation of the normal emulator configuration; no
durability claim is made for it. The separate MinIO fixture/profile was added and passed the restart test. Community
MinIO is archived, so this pinned source build is a trusted loopback-only fixture; production remains a supported
managed-storage deployment, not this unsupported community server.

## Real browser/media path

`BASE=http://localhost:8091 python3 scripts/smoke.py` passed direct multipart upload, SHA-256 processing, two HLS
renditions, authenticated moderator preview/approval, publication, segment retrieval, progress deduplication,
engagement, one qualified view despite duplicate progress and deletion revocation.
See [media result](media-regression-extension.json).

Google Chrome/Playwright on the persistent profile passed upload, moderator preview, approval, creator publish, decoded
HLS playback, auto plus two quality levels, and seek continuation. Browser result reports reviewed=true.
See [browser result](browser-regression-extension.json). Live ClamAV signatures, complex media corpus, perceptual
quality, A/V synchronization and multi-browser/network adaptation were not certified.

## Local API load — both results retained

Workload: authenticated warm GET /api/v1/videos, a tiny synthetic catalog, one development HMAC identity,30 seconds at
requested1000 RPS,100 preallocated VUs, up to500 VUs. API container2 CPU/768MiB, PostgreSQL and Nginx on the same shared
ARM64 developer Docker host; no TLS/CDN, large catalog or production OIDC cost represented.

| Run                          | Completed requests | Achieved RPS |       p95 |         p99 | Failures | Dropped iterations |
|------------------------------|-------------------:|-------------:|----------:|------------:|---------:|-------------------:|
| Overlapped integration tests |             28,738 |       957.87 | 635.41 ms | about1.38 s |        6 |              1,263 |
| Repeat after our tests ended |             30,001 |       999.97 |  2.087 ms |    7.218 ms |        0 |                  0 |

The first run failed the p99<100 ms threshold. The second passed the configured request failure/latency thresholds. This
temporal comparison does not prove a single causal bottleneck or an isolated production capacity limit. Both reports are
preserved: [shared-host result](load-shared-host.json), [repeat result](load-local-1k.json). Do not extrapolate the
repeat to100k RPS, playback authorization SLOs, a100M catalog or millions of viewers. No10k/100k API RPS, CDN
byte-hit/egress, distributed viewer or regional failover result was measured.

## Infrastructure/security checks

Docker multi-stage API/worker builds passed; runtime layers and Maven downloads now cache independently. Persistent
Compose initialized a versioned bucket through a standalone Java SDK bootstrap. MinIO rejected the AWS
AbortIncompleteMultipartUpload lifecycle action; its initializer therefore enables versioning only, while AWS Terraform
retains the supported lifecycle rule. Known upload sessions are reconciled/aborted by the application; unrecorded
provider uploads still need provider maintenance/inventory cleanup in the local fixture.

Terraform init/validate passed with locked AWS provider5.100.0; no plan/apply or cloud creation was executed. CloudFront
custom-policy cookies passed local RSA signature/resource/expiry verification. A real local Nginx cloud-origin container
denied requests without the origin-verification header and accepted the configured header. Worker API/media routes were
denied even with JWTs and worker startup succeeded without a CDN signing key. Governance/erasure gauges,
Python/JavaScript checks and Compose config validation passed. These are implementation checks, not independent security
certification.

## Still pending

Live AWS and CloudFront tests require approved profile/account, bucket owner, region, domain/TLS/origin deployment and
limits. ProviderIT and certify-cdn.py are opt-in and were not run against AWS. Global load scripts/manifests are
provided but not applied. Live ClamAV qualification, Kubernetes apply, network-policy/secret/IAM audit, full chaos
matrix, replica/Object Lock/backups erasure, account-wide privacy erasure, orphan scratch cleanup after crashes,
seamless CDN renewal and immediate edge revocation remain deployment work. Kubernetes emptyDir clears scratch on pod
deletion; Docker named scratch can retain crash leftovers and needs a drained-worker cleanup policy. Primary-bucket DONE
must not be interpreted as a claim covering scratch/RAM/backups/replicas/CDN/downloaded copies.
