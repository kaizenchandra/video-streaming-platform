# Security and production boundaries

Spring Security resource server verifies bearer JWT signatures and expiry; configure an OIDC issuer for production.
Local HMAC dev tokens are enabled only when DEV_AUTH=true. Audience is validated against media.audience (default
streaming-api). Production needs gateway issuer policy, key rotation and secrets injection; do not deploy defaults.
Identity uses subject as owner; separate issuers must not share a subject namespace. User input never specifies owner.
Missing/hidden metadata returns404; mutation requires ownership. Query results contain only published PUBLIC videos;
UNLISTED works by direct known ID.

Part signatures are scoped to a source key, provider upload ID, part number and15-minute expiry. Upload identity remains
private API state. Rate20 initiations/hour/account,100 GiB quota and20 GiB/file are database enforced. Returning
provider session IDs is safe only on authenticated owner endpoints. Checksums are verified by the worker before
decoding; ETag is not treated as a source checksum. MIME metadata is advisory; FFprobe inspects content. Abuse/edge rate
limits, multi-tenant quotas and creator policy require extension.

Playback session cookies are HttpOnly/SameSite Strict and version-path scoped. Set Secure under mandatory HTTPS
production ingress. Authorization checks current deletion, visibility and account status on each origin request; origin
signed URLs stay in internal proxy headers. Never log query signatures or Authorization/Cookie. Nginx access format
currently logs media path but no signed upstream. API exception logging must not include storage URLs. PostgreSQL
credentials and storage secrets in Compose are local-only; use cloud IAM/workload identity instead of static production
keys (SDK adapter currently uses explicit credentials).

Untrusted decoders require isolated nonroot worker pods, no service-account token, restrictive seccomp, read-only root,
bounded scratch, two CPU, memory cgroups, and network policy permitting only database/storage/DNS. Container termination
and leases recover resource exhaustion; uploads should be malware-scanned/moderated before publication. Those
integrations are not implemented. Disable FFmpeg network protocols; file access still requires isolation. The provided
manifest is a starting deployment artifact, not an audited security baseline.

Account/profile preference editing, formal audit log retention, consent management, physical data erasure and content
moderation remain incomplete. Soft deletion denies playback but does not erase bytes. Playback count is per qualified
session and is not bot-proof. Store no direct identity in metric labels; consider pseudonymization/retention for events
and history. Studio DRM, subscriptions and entitlement services are separately scoped.

Extension: manual moderation and role checks, profile/channel APIs, fail-closed ClamAV adapter, file/container
allowlists, object erasure and audited review decisions now exist. The earlier incomplete-feature list describes the
initial baseline. Actual scan definitions, media exploit isolation, provider IAM and edge policy still require staging
qualification. CDN tokens are RSA2048 signed CloudFront custom policies with path and expiry scope; signing keys are
mounted secrets, not repository assets.
