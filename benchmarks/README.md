# Reproducible workloads

Metadata: TOKEN=<jwt> RATE=1000 k6 run metadata.js; repeat RATE=10000 and100000 only on adequate infrastructure with
sufficient maxVUs. Record achieved RPS, dropped_iterations, errors and latency percentiles. Thresholds are proposed
gates, not passed results.

Segment delivery: VIEWERS=1000 SEGMENT_URL=<authorized segment> PLAYBACK_COOKIE='playback=<session>' k6 run segments.js.
Repeat10k/100k viewers only with distributed generators and CDN instrumentation;1M is an unverified capacity scenario.
Distinguish cache-hot single-object stress from realistic Zipf catalog and seeks. Report byte hit ratio, origin egress
and client failures. k6 requests are not decoded playback; browser telemetry is required for startup/rebuffer metrics.

Ingestion: scripts/upload.py can run from parallel processes with separate --state paths to test concurrent source
uploads. Use synthetic fixtures; never route source bytes through API. Processing: scale workers with
`docker compose up -d --scale worker=10`, queue10 test sources, and measure job created-to-ready plus per-attempt
duration.100/1000 workers are scenarios, not laptop tests; resource limit2 cores/worker means20/200/2000 requested
cores. Do not scale beyond provisioned scratch/memory. Source/rendition profile, CPU architecture, threads, cache state
and disk must accompany reports.

No global benchmark results are claimed. See docs/verification.md for small executed checks.

## Distributed validation gates

`distributed-api.yaml` describes ten generators requesting10k RPS each. Create streaming-k6 ConfigMap from metadata.js
and staging-load-token Secret only in an approved test namespace, replace the staging domain, set workload
duration/budget, then apply. Do not apply this against a production endpoint without its traffic budget. Requested100k
RPS is not achieved100k RPS: aggregate actual request counters, errors and dropped iterations from every indexed Job;
generator saturation invalidates the run. Preserve per-generator results outside pod ephemeral storage. Distributed
generators should cover regions/AZs and target a seeded catalog with realistic ownership, search and cache distribution.
The supplied manifest has no central results exporter; configure an approved Prometheus remote-write/output backend
before a large run.

Global playback requires CDN logs/byte hit ratio plus real-browser QoE; HTTP load alone cannot certify decoded startup,
bitrate adaptation or stalls. Start1k metadata RPS, then10k,100k; playback segment tests1k,10k,100k viewer equivalents,
and1M only after distributed generator/CDN budget approval. Add cold/warm cache, long-tail and viral distributions,
seeks and credential expiry.10M is a capacity scenario. Queue/transcode100/1000-worker scenarios require provisioned
CPU/scratch and are not laptop test claims.
