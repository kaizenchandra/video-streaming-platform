# Streamworks — Java 21 video streaming platform

Runnable modular control plane, independent FFmpeg workers, PostgreSQL, private S3-compatible storage and Nginx HLS
delivery. This is an engineering baseline, not a claim of Netflix-scale production readiness.
See [current local verification](docs/verification-local-hardening.md) for measured evidence
and [limitations](docs/limitations.md) before deployment.

**Local durability:** standard Compose uses LocalStack as an emulator; restart testing found that this configuration
loses media objects. For a separate persistent local fixture, use the MinIO override below. Existing LocalStack volumes
and database are preserved; no automatic data migration is performed.

```sh
docker compose -p streaming-durable -f compose.yml -f docker/minio.compose.yml up --build -d
```

In this fixture, upload as creator, sign in as `moderator`, open **Review queue**, preview and approve, then sign in as
the creator again to publish. Scanner bypass remains explicit for synthetic local files.

The durable fixture uses http://localhost:8091 (player),8182 (API),9101 (S3),5545 (PostgreSQL). Its source-built, pinned
MinIO community fixture is for trusted loopback-only testing; the archived community server is not the production
recommendation. Production deployment uses the separately configured managed S3/CloudFront integration. Do not point an
existing metadata database at an empty replacement bucket.

## Run locally

```sh
cd video-streaming-platform
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; otherwise select your JDK 21
mvn test package
docker compose up --build -d
```

Open http://localhost:8090. Development sign in, choose a synthetic or legally owned video, upload, select **My
videos**, wait for READY, then Publish and Play. Suitable source media (360p+) produces at least two renditions. 240p
produces one; smaller media is rejected. Browser demo limits files to 256 MiB for checksum computation; Python uploader
streams larger files using bounded memory. With scanning disabled, the backend limit is20GiB; ordinary ClamAV ingestion
enforces100MiB; the explicit limited windowed MP4/MOV policy admits20GiB. Duration limit is four hours. A real10GiB4K
synthetic source was qualified locally under the limited windowed policy; see the current evidence.

```sh
TOKEN=$(curl -fsS -X POST 'http://localhost:8090/dev/token?user=creator' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
python3 scripts/upload.py example.mp4 --token "$TOKEN"
# Re-run with the same --state file to resume acknowledged parts.
# New uploads need a fresh state file. Do not share state files across users.
```

Generate a source without copyrighted material:

```sh
docker compose exec worker ffmpeg -f lavfi -i testsrc2=size=640x360:rate=24 -f lavfi -i sine=frequency=440 -t 8 -c:v libx264 -c:a aac /tmp/sample.mp4
docker compose cp worker:/tmp/sample.mp4 ./sample.mp4
```

## Verify

```sh
mvn test
# Docker required. Include explicit IT class because default surefire excludes integration-tagged tests:
mvn -Dtest=DatabaseIT,ApiIT,RecoveryIT,WorkerSecurityIT,MigrationIT -Dtest.excluded=none test
docker build -f docker/worker.Dockerfile -t streaming-worker:verify .
python3 scripts/smoke.py
# k6 installed separately; obtain TOKEN above
TOKEN="$TOKEN" k6 run benchmarks/metadata.js
```

On restricted Maven environments, `-o -Daether.syncContext.named.factory=noop` uses already cached dependencies without
modifying Maven lock files. This is unnecessary on normal developer machines.

## Layout

* `src/main/java/com/example/streaming`: catalog, identity, upload, processing, playback, engagement, analytics,
  recommendation and shared modules.
* `src/main/resources/db/migration`: Flyway schema, integrity constraints and indexes.
* `web-player`: catalog, upload, publish, HLS adaptive quality, seek/resume and engagement client.
* `scripts`: resumable streaming uploader and synthetic end-to-end regression.
* `docker`, `compose.yml`: isolated local dependencies, API, worker and authorized origin.
* `helm/platform.yaml`: Kubernetes starting manifests (external database/storage/OIDC required).
* `benchmarks`: separate control-plane and media load workloads.
* `docs`: requirements, HLD/LLD, API, failure matrix, capacity, security, operations and final review.

Development identity and default credentials are local-only. For production disable DEV_AUTH, configure OIDC_ISSUER,
configure media.audience for the expected token audience, inject secrets, deploy private origin policies and TLS, and
complete the production gates in the runbook. LocalStack is an emulator; real S3/MinIO compatibility must be certified
separately. No cloud account is required for local execution.

Browser regression (Node.js required): `npm install --no-save playwright@1.56.1`, `npx playwright install chromium`,
then `node scripts/browser-smoke.cjs /path/to/synthetic.mp4`. Set CHROMIUM_PATH to an existing Chrome executable when
needed.

## Governance and cloud extension

Profiles/preferences and unique creator channels, account deactivation, version-bound manual moderation, ClamAV
fail-closed scanning, durable physical object erasure and reference-aware garbage collection are implemented. Default
Compose deliberately bypasses scanning/review for synthetic development. Use the moderated Compose override for real
review flow; see [hardening](docs/hardening.md).

```sh
# Build the trusted local provider fixture, then test in a disposable project:
docker build -f docker/minio.Dockerfile -t streaming-minio:local .
docker build -f docker/worker.Dockerfile -t streaming-worker:verify .
python3 scripts/chaos.py
# Local Terraform schema checks only; no deployment:
terraform -chdir=cloud/aws init -backend=false
terraform -chdir=cloud/aws validate
```

[AWS integration](cloud/aws/README.md) includes scoped CloudFront cookies, private S3 OAC, native role credentials,
origin protection and opt-in live conformance tests. [Extension verification](docs/verification-extension.md) records
actual outcomes. Staging cloud certification and global-scale measurements require an account/domain and explicit
load/cost limits; local checks cannot establish those claims.

Current executed evidence (see [large-video qualification](docs/verification-large-video.md)): **84 tests passed**,
bounded scratch retention and live ClamAV qualification;
see [local hardening verification](docs/verification-local-hardening.md). Earlier evidence also records
persistent-provider erasure and Chrome manual-review/playback. The local1000-RPS repeat achieved999.97 RPS with p99=
7.218 ms over30 s; the earlier overlapping-load run failed and is preserved
in [verification-extension.md](docs/verification-extension.md). No global-scale certification is claimed.

## Scratch retention and live scanner qualification

Crash leftovers now have volume-specific ownership, live-writer OS locks, legal-hold protection and durable purge
receipts. Old scratch must be adopted with all pre-V7 workers drained. Ordinary ClamAV mode enforces100MiB, signature
freshness, complete responses and a ZIP expansion guard.
See [operations and exact reproduction commands](docs/scratch-retention-and-scanning.md). AWS/CloudFront certification
and global-scale tests remain pending staging access.

## Large-video policy

The explicit `clamav-windowed-mp4` mode supports self-contained unfragmented MP4/MOV up to20GiB with bounded overlapping
scan requests, persisted coverage/hash evidence, strict decoding and mandatory manual moderation. The actual10GiB
synthetic4K workflow passed locally. Its WINDOWED_MP4_V1 verdict has limited signature semantics and is distinct from
whole-file CLEAN. Ordinary ClamAV mode retains its100MiB cap.
See [policy, limitations and exact commands](docs/large-video-scanning.md)
and [measured local evidence](docs/verification-large-video.md).
