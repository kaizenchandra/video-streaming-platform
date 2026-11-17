# Large-media scanner policy

Status: implemented as an explicit limited policy; whole-object security certification remains open.

ClamAV cannot natively apply its complete original-file semantics to the required10GB source. Increasing daemon size
settings does not remove that design limitation. A locally reproducible workflow needs bounded memory, fail-closed
behavior and an honest verdict while a whole-object provider remains unqualified.

Keep the existing single-file ClamAV path. Add an opt-in self-contained unfragmented MP4/MOV policy with bounded
structural checks, complete bounded metadata and overlapping64MiB/64KiB raw requests. Persist byte
submission/hash/engine evidence and use WINDOWED_MP4_V1 instead of CLEAN. Always require manual moderation and enforce
the selected policy during approval and local playback. No new service, broker or database is required.

This preserves a runnable10GB upload-to-HLS path with bounded memory, at the cost of repeated IO, complete rescan on
retry, restricted formats and loss of whole-file/long/offset/embedded-archive signature semantics. Reviewers see the
limitation. The qualification-only any-offset canary proves submission, not production malware efficacy; the original
native EICAR expectation failure is retained. See [policy](../large-video-scanning.md)
and [executed evidence](../verification-large-video.md). Native whole-object security, broad corpus/isolation and
AWS/global qualification remain deployment gates.
