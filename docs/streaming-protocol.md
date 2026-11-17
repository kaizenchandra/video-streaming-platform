# HLS and delivery

master.m3u8 selects bandwidth variants; each height/index.m3u8 lists segment_NNNNN.ts and ENDLIST. A player downloads a
few seconds at a time, estimates throughput/buffer state, switches renditions on aligned keyframes, and seeks by
playlist timeline. Short segments improve reaction/startup but increase object/request overhead. Startup, buffer and
retry logic use hls.js 1.6.13. Safari can use native HLS; Chromium/Firefox generally use MediaSource with hls.js. Codec
availability still depends on browser/OS. Quality selection is exposed by the client; native HLS keeps device controls.

Local Nginx is a secure origin, not a CDN: it checks current visibility/account status on every request and proxies
signed S3 GETs without exposing credentials. Cookie path scopes a session to a version; expiry is four hours, and the
user restarts playback to renew. Video bytes never traverse Spring MVC. The local cache policy is private/no-store to
make revocation immediate on new requests; already buffered/downloaded content cannot be recalled.

Cloud extension: immutable segments cache for a year, origin shield, private bucket policy restricted to CDN origin
identity, CDN signed cookies verified at the edge. Authorize manifest/session with API; strip authentication from cache
keys after edge validation. Query tokens otherwise fragment cache entries. Cache keys include immutable version and
canonical path; cache public master briefly only if visibility policy permits. Revocation requires short credentials
plus purge/deny policy and a defined residual window; a cached private response must never bypass edge validation. The
local per-segment DB authorization would not scale to millions of viewers and must be replaced by edge validation before
that workload.

QoE instrumentation extension: record play-request to first decoded frame (startup), waiting intervals excluding
seeks/initial load (rebuffer time), stalls per played minute, quality switches, bytes/media duration (delivered
bitrate), fatal errors. Browser baseline displays errors but does not export comprehensive QoE telemetry. Measure
cold/warm cache separately and include device, network, RTT and encoding ladder.

DASH uses MPD rather than HLS playlists; CMAF can share fMP4 segments between protocols. Signed URLs/cookies control
access only. AES-HLS encrypts segments but client key access is not studio DRM. Widevine/PlayReady/FairPlay need
licensed provider, encrypted packaging, license entitlement and compatible device/player integration; none is claimed
here.
