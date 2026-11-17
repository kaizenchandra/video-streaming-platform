# Large-video scanning and local qualification

## Supported policy and its limits

`MEDIA_SCAN_MODE=clamav-windowed-mp4` is an explicit opt-in policy for self-contained, **unfragmented MP4/MOV** sources
up to20GiB. Files above the ordinary single-input budget get the distinct verdict **WINDOWED_MP4_V1**, never CLEAN.
Existing `clamav` remains capped at its configured single-input budget (100MiB by default); disabled scanning remains an
explicit development setting.

ClamAV documents a2GB per-file design limit in
its [official configuration reference](https://github.com/Cisco-Talos/clamav/blob/main/docs/man/clamd.conf.5.in).
Raising StreamMaxLength alone does not remove it. This implementation submits a large source as bounded, overlapping raw
ranges, plus bounded complete metadata. It **does not recreate a whole-file ClamAV scan**. Original-file hash
signatures, offsets relative to the original object, long signatures exceeding overlap, cross-window logical signatures,
embedded compressed objects and arbitrary archive semantics can be lost. A20GiB limit is the product contract, not
qualification of every size/format/corpus. A provider supporting the required whole-object semantics remains a
production security gate.

The worker always leaves a windowed result PENDING for manual moderation, even if MODERATION_AUTO_APPROVE=true. The
moderator sees the limited policy and byte coverage in the review queue. Approval requires the enabled window policy,
complete evidence, full submitted-byte coverage and a matching authoritative source SHA-256. Review preview, normal
session creation and local per-segment authorization enforce the current scan policy. Disabling windowed policy
therefore denies new sessions and local delivery of those versions. Already-issued CDN credentials follow their
TTL/revocation policy; live edge certification remains pending.

“Covered” means bytes submitted in accepted scanner requests. It does not prove that all scanner signatures/decoders
analyzed every possible interpretation of those bytes. A complete record proves this submission protocol and source
identity, not absence of malware. Manual review does not remove those limitations.

## Protocol and bounded resources

1. Download the private source directly from object storage to the attempt's locked scratch directory; verify its
   expected SHA-256.
2. For a source above the single-input budget, validate ISO BMFF headers without loading media data. Require exactly one
   ftyp/moov/mdat; bound metadata to32MiB, nesting to16 and box count to100,000. Reject unsupported top-level boxes,
   compressed/reference movie structures and encrypted/unsupported sample entries. Require self-contained data
   references, approved sample descriptions and chunk offsets inside mdat.
3. Scan the complete bounded moov metadata separately. Stream the source through64MiB windows with64KiB overlap,
   using64KiB buffers. No full-window or full-object byte array is allocated.
4. Verify signature freshness and a consistent engine/database version across the scan. Any transport error, rejection,
   timeout, cancellation, changing database or changing source leaves incomplete evidence and prevents publication.
5. Hash unique source bytes while streaming; require the resulting hash to equal the ingestion hash. Persist policy,
   bytes, overlap, accepted windows, transmitted bytes, hash, completion and engine/database evidence in V9
   `scan_evidence` JSONB plus the existing scanner fields.
6. Decode/transcode only after the policy succeeds. FFprobe/FFmpeg use bounded output, timeouts, file-only protocols and
   a container whitelist. Decoder corruption is fatal with xerror/err_detect explode. Reject nonfinite duration,
   unsupported dimensions, frame rates above120fps, attached-picture video selection and more than16 streams. Individual
   native allocations are capped at256MiB; codec/filter threads are bounded. These controls supplement container
   CPU/memory/storage/isolation limits.
7. Verify outputs, publish the immutable attempt pointer, then require manual approval before creator publication.
   Existing lease fencing, retry/backoff, source integrity and scratch cleanup apply.

The source deadline defaults to1 hour (maximum2 hours); individual scanner requests default to10 minutes and have
socket/read/output bounds. Retrying an attempt rescans the source from the beginning. Partial evidence is diagnostic,
not a trusted continuation checkpoint. Upload resume remains independent and durable at the multipart boundary.
Legal-hold and scratch retention semantics are unchanged.

| Setting                              |   Default | Meaning                                                               |
|--------------------------------------|----------:|-----------------------------------------------------------------------|
| MEDIA_SCAN_MAX_BYTES                 | 104857600 | Single-request/source budget for ordinary mode                        |
| MEDIA_SCAN_WINDOW_BYTES              |  67108864 | Maximum window, also bounded by single-request budget                 |
| MEDIA_SCAN_OVERLAP_BYTES             |     65536 | Raw-pattern continuity; cannot preserve arbitrary signature semantics |
| MEDIA_SCAN_SOURCE_TIMEOUT_MS         |   3600000 | Whole source deadline, bounded to7200000                              |
| MEDIA_SCAN_MAX_SIGNATURE_AGE_SECONDS |    604800 | Runtime signature freshness; do not disable in production             |

The structural parser is deliberately a restricted gate, not a complete ISO BMFF validator or exploit defense. Large
MKV/AVI/fragmented MP4, encrypted media, unsupported codecs, references and oversized metadata fail closed. Small-file
behavior remains the original scanner followed by the strict decoder. Browser checksum upload remains capped at256MiB;
use the bounded Python multipart client for large sources.

## Qualification canaries and retained failure

The first large test incorrectly assumed native EICAR detection would act as an arbitrary byte-pattern detector when
the68-byte fixture was embedded in raw v210 pixels. It was **not detected**, and the qualifier failed before any
upload/publication. [Retained result](large-scan-initial-failure.json). Project maintainers discuss EICAR's
hash/format-sensitive behavior in [this upstream issue](https://github.com/Cisco-Talos/clamav/issues/1354); no
root-cause/security-certification claim is inferred from that discussion.

Coverage tests now use a separately labeled benign `Streamworks.Coverage.Canary` body signature with any-offset
matching, following
the [official extended-signature format](https://docs.clamav.net/manual/Signatures/ExtendedSignatures.html). It lives
only in `streaming-clamav:coverage-qualified`, the private qualification image. The ordinary moderated scanner image has
**no test rule**. The rule's SHA-256 is recorded with results. Detected boundary/late/tail canaries validate byte
submission and overlap, not the efficacy of the production signature corpus. Standard raw EICAR and archived EICAR tests
remain separate and use native signatures.

The read-only bind-mounted test rule initially prevented the scanner image's startup chown. A dedicated image layer with
the correct ownership fixed the fixture. Scanner reload also intentionally blocks requests under
ConcurrentDatabaseReload=no; the qualification runner waits with a bound, while runtime failures remain fail-closed.

## Reproduce

From this project directory with JDK21, Docker Compose>=2.24.4, signature-update network access and roughly80GiB of
available working/Docker storage, including at least50GiB remaining on the worker filesystem before its download gate:

```sh
mvn -Dtest=MediaTest,ProtectionTest,AuthenticationTest,ScannerPolicyTest,LargeScannerTest,WindowPolicyTest,DatabaseIT,ApiIT,RecoveryIT,WorkerSecurityIT,MigrationIT -Dtest.excluded=none package
docker build --target api -t streaming-api:large-qual .
docker build --target worker -t streaming-worker:large-qual .
docker build -f docker/minio.Dockerfile -t streaming-minio:local .
python3 scripts/qualify-scanner.py --build --keep
# Generates real 4K v210 MOV samples, checks canaries, uploads/resumes/completes,
# scans/transcodes, manually approves, fetches and independently decodes HLS, then deletes/revokes.
python3 scripts/qualify-large-video.py
# Separate actual FFmpeg negative corpus:
docker run --rm --memory 512m --cpus 2 --entrypoint java streaming-worker:verify \
  -Dloader.main=com.example.streaming.processing.AdversarialMediaQualification \
  -cp /app.jar org.springframework.boot.loader.launch.PropertiesLauncher
# Only the dedicated scanner test fixture is removed here:
docker compose -f docker/scanner-qualification.compose.yml down --remove-orphans
```

The default fixture is48624fps frames at3840×2160, totaling10,749,547,065 bytes, with no sparse or filler padding.
`--frames 6` is the inexpensive CI variant and explicitly **does not qualify10GB**. The script uses a random disposable
project, scoped credentials, bounded8MiB multipart buffers, a persisted/reopened resume checkpoint, replayed
acknowledgement and concurrent completions. It removes only its own containers/volumes/generated media by default;
`--keep-fixture` is for local investigation. Test mutations are restored in a Java finally block and checked by
full-file SHA-256; only generated synthetic fixtures are used.

Enable the limited policy in a separate development stack only after building/upgrading API and workers together through
V9:

```sh
docker compose -p streaming-large -f compose.yml -f docker/minio.compose.yml \
  -f docker/moderated.compose.yml -f docker/moderated-large.compose.yml up --build -d
```

Choose nonconflicting host ports when another stack already uses the durable profile's ports. Drain old workers before
migration/activation; an old worker/API does not understand the new evidence/verdict. Never point an existing metadata
database at an empty replacement bucket. For production, replace development identity/credentials/storage, enforce
worker egress and network policies, and complete independent scanner/provider, corpus, isolation, retention and
operational review.

AWS/CloudFront certification and global-scale/QoE testing remain **pending staging access**.
See [executed local evidence](verification-large-video.md).

V9 is additive but verdict semantics require coordinated deployment. Do not roll back to V8 while windowed
versions/sessions remain admitted; disable admission, apply the credential revocation policy and prefer a forward fix.
Old binaries do not enforce the new verdict policy.
