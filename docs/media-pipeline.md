# Media and upload protocol

POST uploads requires a creator-owned DRAFT/FAILED video, expected bytes, lowercase source SHA-256 and Idempotency-Key.
Reserve 100 GiB/account by default; max source 20 GiB. Client uses 8 MiB parts (5 MiB minimum except last); provider
part numbers 1–10000 and 5 GiB max part; signing batches at most 100 and 15-minute URL lifetime. Complete lists provider
parts, not client-supplied ETags; multipart ETags are not SHA-256. Integrity is pending until the worker streams the
source checksum. Source has a unique upload UUID key. Abort releases quota after provider success; expired sessions
reconcile every minute after 24 h. A lost complete response is safe to retry.

FFprobe extracts video height, audio presence and duration. Supports 240p–4320p source, <=4 h; output ladder
240/360/480/720/1080 constrained by source height. Higher ladders/GPU/codecs require profiling. Sequential renditions
conserve memory; jobs parallelize across worker replicas. libx264 yuv420p + optional AAC stereo provides common HLS
compatibility. Four-second forced keyframes align segment boundaries; variable source frame rates and unusual sources
need qualification. HLS MPEG-TS chosen for broad client compatibility; DASH/CMAF, subtitles and multiple audio tracks
are not implemented.

Executable paths are server config, ProcessBuilder arguments are lists, no shell, output capture capped at 128 KiB,
FFprobe timeout 30 s, per-rendition FFmpeg timeout 5 h. Two encoder threads and container CPU/memory limits bound
compute. Scratch requires 50 GiB available; source bound is 20 GiB. Kubernetes ephemeral-storage limit bounds disk; it
can evict a worker and retry, so provision headroom based on profiles. Timeout or lease loss interrupts the process and
kills it. Source media remains untrusted: production must add demuxer allowlists, seccomp, restricted
filesystems/network and scanning/moderation before publication.

Each variant must end with ENDLIST and reference existing nonempty TS files. All artifacts upload to
videos/{id}/{attempt}/; HEAD size verifies provider writes; master uploads last; DB pointer commits after verification.
Local validation checks packaging structure, not complete A/V synchronization or perceptual quality. Smoke test fetches
all segments; production requires decode, timestamp, A/V sync and player certification on a larger media corpus.

Attempt output is immutable; retries never modify published paths. Fencing prevents duplicate authoritative publication.
Crash leftovers require inventory-based garbage collection after safety window; do not delete an attempt still
referenced by a video or unexpired session. Lifecycle can abort unrecorded multipart uploads after two days.
Cancellation API for running jobs is not provided; deletion blocks final publication but may waste remaining compute.

Provider
docs: [S3 multipart limits](https://docs.aws.amazon.com/AmazonS3/latest/userguide/qfacts.html), [AWS Java SDK setup](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup.html), [FFmpeg HLS muxer](https://ffmpeg.org/ffmpeg-formats.html#hls-2).
SDK 2.27.21 follows existing repository pin, compiles on Java 21; security/version upgrades need CI and real-provider
certification.
