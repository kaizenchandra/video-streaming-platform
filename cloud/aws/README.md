# AWS S3 and CloudFront integration

Terraform configuration creates a versioned, encrypted private S3 bucket, blocked public access, scoped CloudFront
origin access control, trusted public key/key group and signed-cookie media behavior. It uses locked AWS
provider5.100.0. It does not deploy PostgreSQL, IdP, API compute, DNS, ACM certificate or origin security groups.
`terraform init -backend=false` and `terraform validate` are local checks; no resources are created until an explicit
plan/apply. Configure a protected encrypted remote Terraform state before applying; origin verification token is
sensitive but remains in state.

1. Supply region, globally unique bucket_name, domain, application_origin (HTTPS ALB hostname), certificate_arn (ACM
   us-east-1 matching domain), cloudfront_public_key_pem and origin_verify_secret (random32–128 alphanumeric
   characters). Generate an RSA2048 key pair outside the repository; public key goes to Terraform, PKCS8 private key
   goes only to a mounted secret file. Neither private key nor tfvars/state belongs in source control.
2. Protect the application origin using the matching X-Streaming-Origin secret plus an ALB security group restricted to
   the AWS-managed CloudFront origin-facing prefix list. The supplied nginx.cloud.conf.template enforces the secret;
   configure API_UPSTREAM and ORIGIN_VERIFY_SECRET. TLS at ALB is mandatory. Do not expose API or ordinary local Nginx
   directly to the internet.
3. Deploy API/worker using S3_CREDENTIALS_MODE=default, S3_ENDPOINT='', S3_PUBLIC_ENDPOINT='', S3_PATH_STYLE=false,
   AWS_REGION and S3_BUCKET. AWS SDK default credential chain supports role credentials, including STS web identity.
   Scope runtime IAM: API multipart/read source; workers read sources and put/HEAD media; janitor list versions/delete
   versions/abort multipart; certification principal additionally reads bucket
   policy/encryption/versioning/public-access settings. The baseline shared deployment credential provider should be
   split by role in production. No IAM policy allows public bucket reads.
4. Set CDN_ORIGIN=https://your-domain (no slash/path), CDN_KEY_ID=Terraform key_id, CDN_PRIVATE_KEY_FILE=
   /mounted/private-key.pem and CDN_TTL_SECONDS=300. Route the same domain through CloudFront to the app; this allows
   host-only Secure/HttpOnly signed cookies without a broad parent-domain cookie. Media paths become
   /videos/{video}/{attempt}/master.m3u8, matching the private S3 keys. Uploads still go directly to S3. `/api/*` and UI
   go uncached to the application origin; /videos/* uses trusted key-group verification at the edge. API response emits
   all three CloudFront cookie headers.
5. New cloud-encoded objects use Cache-Control public,max-age=0,s-maxage=31536000,immutable: shared edge caches retain
   immutable media, browser requests still reach authorization boundaries. Existing objects may need metadata migration
   or reprocessing. CloudFront validates cookies for cached content. Cookie values are excluded from cache variation by
   the managed cache policy. Cache behavior and propagation must be tested live.

The default cookie TTL is five minutes (configurable30–900 s). Local origin mode checks current database policy per
request. CloudFront mode accepts an issued cookie until expiry, including after deactivation, rejection or deletion;
already buffered/downloaded bytes cannot be recalled. Cache invalidation alone does not revoke valid cookies because
they can fetch origin bytes again. For immediate studio-policy revocation add an edge denylist or key-group rotation
with a defined propagation policy. The client currently restarts playback to renew credentials; seamless renewal remains
work.

Live staging certification:

```sh
export AWS_PROFILE=your-approved-staging-profile AWS_REGION=us-east-1
export S3_BUCKET=your-staging-bucket S3_EXPECTED_OWNER=123456789012
sh scripts/certify-provider.sh
# Configure short TTL30..60 for this bounded expiry test, plus an approved published synthetic VIDEO_ID.
STAGING_ORIGIN=https://your-domain TOKEN=<oidc-token> VIDEO_ID=<uuid> python3 scripts/certify-cdn.py
```

ProviderIT creates only one random sources/UUID key with synthetic bytes and cleans all its versions. It verifies
expected bucket owner, public-access block, policy status, versioning/encryption, multipart/idempotent completion, valid
signed GET, anonymous/tampered denial and permanent version erasure. It writes a report only on success. This is a
scoped staging conformance test, not an independent security certification or IAM pen test. No live run has been claimed
without the actual report. CDN script verifies real delivery, warm-cache anonymous denial, tampered cookies, path scope
and expiry. Further gates: wrong distribution OAC, cross-role authorization, Object Lock, replication erasure,
browser/proxy cookie header preservation, provider outage and load.

Official
references: [CloudFront signed cookies](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-signed-cookies.html), [S3 origin access control](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-s3.html), [permanent S3 version deletion](https://docs.aws.amazon.com/AmazonS3/latest/userguide/DeletingObjectVersions.html).

Worker mode denies control-plane routes and does not load the CDN private key. Pass CDN_ORIGIN to workers for
shared-cache media metadata, but mount the signing key only in API pods. Workers still need PostgreSQL/storage
credentials; do not expose their management port publicly.
