# Architecture review — 40 questions

1. **Netflix for100M users** Separate regional metadata/entitlement control from immutable media/CDN data; partition
   metadata by ownership and scale reads/cache only after workload measurement. Membership count alone does not size
   delivery.

2. **YouTube billions of views** Direct uploads, independent durable processing and global CDN dominate; partition
   high-volume analytics into a log and batch aggregates. Use moderation/abuse and cost quotas as first-class admission
   controls.

3. **Metadata versus media** Small transactional records need indexes/consistency; large immutable bytes need object
   durability and edge caching. JVM proxies add avoidable bandwidth/memory pressure.

4. **Resumable multipart** Server persists upload/key/provider ID and signs numbered parts. Client retries a part
   independently; completion uses authoritative ordered parts, then verifies final object.

5. **Direct upload** Scoped expiring URLs allow client-to-storage transfer without occupying Spring request threads or
   heap; quotas/admission remain API decisions.

6. **10GiB safely** Use bounded multipart buffers and streaming SHA-256/download to disk, reserve quota/scratch, isolate
   workers and enforce CPU/memory/time limits.10GiB is a contract scenario, not an executed fixture.

7. **Distribute jobs** Database claims with SKIP LOCKED and lease/heartbeat; batch schedulers or queue-triggered durable
   jobs are alternatives when workload justifies additional operations.

8. **Duplicate processing** Duplicate compute can happen. Unique active job, attempt UUID output prefixes and fenced
   current-attempt commit prevent duplicate authoritative publication.

9. **Worker crash** Heartbeat stops; lease expires; new attempt retries from source. Old outputs remain unreferenced for
   delayed GC.

10. **FFmpeg multiple renditions** Server ladder selects heights <=source; libx264/AAC encodes each independently with
    four-second forced keyframes and HLS packaging.

11. **HLS** A master playlist describes variants; media playlists enumerate playable segments. Player selects a variant
    and fetches segment timeline over HTTP.

12. **DASH versus HLS** MPD versus m3u8 manifests and different ecosystem/native support; both can use CMAF/fMP4.
    Baseline uses HLS MPEG-TS.

13. **ABR** Maintained player estimates throughput/buffer occupancy and chooses sustainable variants. It lowers quality
    to protect buffer and raises when safe; no custom algorithm here.

14. **Aligned keyframes** Switching variant at independent decode boundaries avoids decoder corruption and timeline
    discontinuities. Force aligned boundaries and validate timestamps on qualification corpus.

15. **Manifests/segments** Master references variant playlists; variants reference immutable segment keys. Publish
    pointer only after those exact objects exist.

16. **CDN necessity** Millions of viewers imply tens of Tbps. Edge caches reduce latency and origin bandwidth; API
    replica count cannot solve media egress.

17. **Origin overload** Use byte-hit metrics, origin shield, cache coalescing, long-lived immutable segments and
    capacity limits. Avoid token query cache fragmentation after edge auth.

18. **Viral video** A hot immutable path is ideal for CDN caching; shield cache-fill storms and separate metadata
    popularity updates from segment fetches.

19. **Private playback** Check current visibility/ownership, mint scoped expiring credentials, restrict origin, validate
    at edge/origin and define revocation window. Local origin checks policy every request.

20. **Signed URLs versus DRM** Signatures authorize delivery; DRM controls encrypted media licenses/device playback
    under licensed systems. A downloaded authorized clear segment is not protected by URL expiry.

21. **Video versions** Each processing attempt writes new UUID paths; current_attempt is one database pointer. Never
    overwrite published artifacts; retain old versions while sessions reference them.

22. **No partial publication** Require valid ENDLIST, nonempty referenced segments, successful thumbnail and all output
    HEAD checks, then atomic fenced metadata commit.

23. **History across devices** Monotonic per-session sequence; newest-created session wins. Store position every15
    seconds, allow seeks and ignore older-device updates after newer session wins.

24. **Accurate views** Define qualified session using elapsed and position thresholds, once per session. This is
    auditable but requires fraud controls/dedup windows for unique-human claims.

25. **Duplicate analytics** Lock session to set qualified once; event insert shares transaction. Consumer increment and
    processed marker share one transaction, so retries cannot permanently double-count same event.

26. **Recommendation systems** Popularity is simple but biased; content similarity handles cold items; collaborative
    signals learn affinity; embeddings retrieve semantically; two-stage systems rank a bounded candidate set.

27. **Baseline personalization** Score log views, reciprocal freshness and watched-category affinity for PUBLIC
    published eligible videos. Current query-time model has no diversity normalization or ML claims.

28. **Scale search** PostgreSQL GIN full-text starts simple. Adopt dedicated search for multilingual/fuzzy/autocomplete
    or index scale; CDC/outbox indexing must recheck authoritative eligibility and support rebuilds.

29. **Catalog consistency** PostgreSQL writes and version CAS govern metadata; published requires READY/current pointer.
    Search query reads same durable public eligibility, avoiding stale private index leakage.

30. **Deletion/CDN** Soft delete immediately denies local origin auth; cloud requires purge/denylist plus bounded
    credential/cache lifetime. Physical erasure and already-downloaded bytes are separate issues.

31. **Cross-region replication** Copy immutable versions asynchronously, verify manifests/object inventory per region,
    then advertise region availability. Replication lag affects publish/failover, not just cost.

32. **Regional outage** Fence writer, route auth to verified standby, recover metadata/media within tested RPO/RTO.
    Cached playback continuity depends on edge credentials and content availability.

33. **Encoding cost** Avoid upscaling, bound ladder, reuse verified outputs when safe, benchmark per-title
    encoding/hardware profiles and prioritize popular content; reserved/spot workers need retry budget.

34. **Playback quality benchmarks** Measure first decoded frame, stall ratio excluding seeks, delivered bitrate and
    failures with real browsers under controlled RTT/bandwidth/cache/device distributions.

35. **10M viewers** At5Mbps average delivery is50Tbps plus overhead. Multi-CDN/regions and edge auth are essential;
    local Compose does not represent that topology.

36. **Bandwidth calculation** Viewers × average delivered bit/s; divide by8 for bytes, multiply duration for transfer.
    Origin uses byte miss ratio, not session count or request-only hit ratio.

37. **FFmpeg exhaustion** Nonroot sandbox, constrained protocols/arguments, cgroup CPU/memory, disk quotas, process
    timeouts and bounded logs. Malicious-file qualification/scanning remains a deployment gate.

38. **Queue backlog** Measure oldest job age and service rate; autoscale workers on queue work seconds, enforce
    admission quotas/priorities and cap retries. CPU-only HPA misses long-job backlog.

39. **Upload-to-publish safety** Persist quota/session, reconcile external storage ambiguity, enqueue in source
    transaction, checksum before decode, immutable verified outputs, fenced pointer and explicit owner publish.

40. **Production verification** Real-provider fault tests, browser/media corpus, load/soak, restore/failover,
    IAM/TLS/OIDC audience, media sandbox, monitoring, retention/erasure and cost limits. Current evidence does not
    certify those gates.
