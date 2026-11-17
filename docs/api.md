# API behavior

See [OpenAPI](openapi.json) for routes and request models. All /api calls require a bearer JWT. Dev token route is POST
/dev/token?user=creator when enabled. Create returns201; other commands return200 (including empty bodies). RFC9457
Problem Details covers validation/conflicts; Spring Security may return empty401 for invalid bearer. GET upload is
owner-only, catalog excludes hidden/deleted videos. PATCH requires decimal version in If-Match and all editable
metadata; publishes by setting published=true only after READY.

Examples:

```json
{"title":"Example","description":"Synthetic","category":"education","tags":"java video","visibility":"PUBLIC","published":false}
```

POST /videos returns an object including id, version, state and owner_id. POST /videos/uploads with Idempotency-Key:

```json
{"videoId":"11111111-1111-1111-1111-111111111111","bytes":8388608,"sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}
```

Response includes id, video_id, state, object_key, multipart_id and expiration. POST /uploads/{id}/parts body {"first":
1,"count":1} returns [{"number":1,"url":"scoped-presigned-PUT"}]. PUT raw bytes directly to that URL. Complete body {}
returns durable COMPLETED state; duplicate complete returns the same session; changed initiation parameters under same
key409. Abort is idempotent only for already aborted; completed409. Source integrity is confirmed by worker, not ETag.

POST playback-sessions returns {"sessionId":"uuid","manifest":"/media/videos/id/attempt/master.m3u8","resume":0} and
scoped HttpOnly cookie. Progress: {"sessionId":"uuid","sequence":1,"position":15.5,"completed":false}. Duplicate/old
sequences are no-ops. GET continue-watching returns progress plus title. Likes use PUT/DELETE and unique(user,video);
comments use POST {"body":"Text"}, GET, DELETE comment ID.

GET videos/search accepts q, category, creator, maxDuration, limit (1–100), before (last video UUID); cursor resolves
(created_at,id). Lists are bounded, not count-based. Cursor deletion retains the soft row. Recommendations uses
explainable popularity/freshness/category affinity. Trending currently uses catalog ordering; time-window ranking
remains a limitation. Error example: {"type":"about:blank","title":"Conflict","status":409,"detail":"Media not ready"}.
Do not rely on internal database messages.

Identity/channel, review, legal-hold and erasure APIs are specified in OpenAPI and [hardening](hardening.md). Moderation
decisions require a trusted MODERATOR token role and bind to the supplied attempt plus If-Match version. Erasure status
remains accessible to the owner after the normal video resource is deleted. Cloud playback returns three scoped
CloudFront cookies and credentialTtl; local origin mode retains its session cookie.

Part URL responses include contentLength. The public multipart contract uses8 MiB parts except the last and signs the
expected length; clients cannot request part numbers beyond the declared source. Moderator preview POST
/videos/{id}/review-playback-sessions is separate from ordinary playback, expires in15 minutes locally, and does not
record watch history/views.

Scanner-enabled upload initiation returns413 above the configured scan budget (qualified default100MiB), before quota
reservation or URL issuance. API and worker must share this policy. Owner erasure status adds `scratchPending`: the
number of tracked attempts without a purge receipt, independent of primary-bucket DONE. Legacy unknown paths, backups,
replicas, CDN and disk sanitization are not included.
See [retention/scanner protocol](scratch-retention-and-scanning.md).

Explicit `clamav-windowed-mp4` mode admits up to20GiB, with container/policy checks after source completion; unsupported
large containers are blocked before encoding. WINDOWED_MP4_V1 requires complete matching source evidence and manual
approval, including when development auto-approval is configured. Moderator queue rows include scan_result, scan_policy,
scanned_bytes and source_bytes. Original whole-file signature semantics are not preserved;
see [large-video policy](large-video-scanning.md).
