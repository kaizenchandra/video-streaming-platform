# MASTER PROMPT: Design and Develop a Production-Grade Large-Scale Video Streaming Platform Like Netflix or YouTube Using Java 21 and Spring Boot

## 1. ROLE AND ENGINEERING RESPONSIBILITIES

Act as a **Principal Software Engineer, Distinguished Distributed Systems Architect, Solution Architect, Java 21 Expert,
Video Streaming Architect, Cloud Infrastructure Engineer, Media Processing Engineer, Security Architect, and Site
Reliability Engineer** with 15+ years of experience building high-throughput distributed platforms.

Your task is to **design, develop, test, benchmark, document, and deploy a complete large-scale video streaming platform
inspired by Netflix and YouTube using Java 21 and Spring Boot**.

Treat this as a real enterprise product implementation, not merely a theoretical system design interview exercise.

You are responsible for delivering:

1. Functional and non-functional requirements
2. High-Level Design (HLD)
3. Low-Level Design (LLD)
4. Video upload and ingestion pipeline
5. Distributed video transcoding
6. Adaptive bitrate streaming
7. Video packaging using HLS and/or MPEG-DASH
8. Object storage integration
9. CDN-ready video delivery
10. Video catalog and metadata management
11. Search and discovery
12. Playback authorization
13. Watch history and playback resume
14. User engagement
15. Recommendation system
16. Event-driven processing
17. Concurrency and distributed consistency
18. Fault tolerance and disaster recovery
19. Security and content protection
20. Observability and performance engineering
21. Automated testing and load testing
22. Docker and Kubernetes deployment
23. Functional video playback client
24. Complete source code and documentation

**Do not stop at architecture diagrams, pseudocode, partial implementations, or illustrative code snippets. Deliver a
runnable, maintainable, tested implementation with backend services, working video processing, playback, infrastructure,
and operational documentation.**

### Mandatory engineering principles

- Use Java 21 and a verified compatible Spring Boot release.
- Follow existing repository instructions, dependency versions, architecture, and coding conventions when working in an
  existing project.
- Apply DDD, SOLID, Clean Architecture, and Hexagonal Architecture pragmatically.
- Prefer cohesive modular boundaries over unnecessary microservices.
- Separate control-plane APIs from high-bandwidth video data delivery.
- Avoid routing all video bytes through Spring Boot application servers.
- Use bounded queues, retries, connection pools, timeouts, and compute resources.
- Address idempotency, concurrency, ordering, transaction boundaries, and partial failures.
- Use immutable media objects and explicit publishing state transitions.
- Treat video processing jobs as resumable, idempotent workflows.
- Do not introduce Kafka, Elasticsearch, Cassandra, or additional service boundaries without architectural
  justification.
- Never fabricate test results, benchmark measurements, or production-readiness claims.
- Clearly distinguish implemented functionality, architectural extensions, and unverified performance targets.
- Make routine technical decisions independently and explain significant trade-offs.

---

# PART A — PROJECT OBJECTIVE

## 2. PRIMARY OBJECTIVE

Build a horizontally scalable video streaming platform supporting both:

**Video-on-Demand (Netflix-like capabilities)**

- Video catalog
- Secure playback
- Adaptive bitrate streaming
- Watch history
- Continue watching
- Personalized recommendations
- Subscription-based access as an optional module

**User-Generated Video Platform (YouTube-like capabilities)**

- Creator accounts
- Video uploads
- Video processing
- Video publishing
- Public/unlisted/private videos
- Video search
- Likes and comments
- Creator channels
- View counts
- Recommendations

Design the underlying media infrastructure to support both product models.

For the initial implementation, prioritize a complete **Video-on-Demand and creator-upload workflow** with clearly
documented extension points.

Do not attempt to reproduce every feature of Netflix and YouTube simultaneously at the expense of correctness.

### Example end-to-end scenario

A creator uploads a 10 GB, 4K video.

The system must:

1. Authenticate and authorize the creator.
2. Validate metadata and upload quotas.
3. Create a durable upload session.
4. Generate scoped multipart upload URLs.
5. Upload the video directly to S3-compatible object storage.
6. Verify upload completion and object integrity.
7. Atomically transition the asset to a processing state.
8. Record a durable processing event.
9. Schedule a video-processing job.
10. Probe the video with FFprobe.
11. Validate codec, resolution, duration, and media streams.
12. Generate multiple video renditions.
13. Package renditions into HLS or MPEG-DASH.
14. Generate thumbnails and preview images.
15. Store output artifacts in object storage.
16. Validate manifests and media segments.
17. Publish the playable video version.
18. Make video metadata available in the catalog.
19. Allow authorized viewers to initiate playback.
20. Serve manifests and segments through a CDN-ready origin.
21. Adapt video quality according to network conditions.
22. Record playback events asynchronously.
23. Update watch history and recommendations.
24. Recover safely from upload, transcoding, storage, or event-processing failures.

---

# PART B — FUNCTIONAL REQUIREMENTS

## 3. USER AND IDENTITY MANAGEMENT

Implement:

- User registration through an identity provider or local development identity setup
- User profiles
- Creator profiles/channels
- Authentication
- Authorization
- Account deactivation
- User roles
- Media ownership
- User preferences
- Playback sessions

Use Spring Security with OAuth2/OIDC or JWT where appropriate.

Do not build a proprietary authentication protocol unless explicitly justified.

---

## 4. VIDEO UPLOAD AND INGESTION

Support:

- Upload initiation
- Resumable multipart uploads
- Parallel part uploads
- Upload progress
- Upload cancellation
- Upload expiration
- Upload completion
- Upload integrity verification
- Duplicate completion handling
- Creator storage quotas
- Upload rate limiting

### Required APIs

- `POST /api/v1/videos/uploads`
- `GET /api/v1/videos/uploads/{uploadId}`
- `POST /api/v1/videos/uploads/{uploadId}/parts`
- `POST /api/v1/videos/uploads/{uploadId}/complete`
- `POST /api/v1/videos/uploads/{uploadId}/abort`

Use direct-to-object-storage uploads when possible.

Avoid buffering large videos inside JVM heap.

Define object-storage multipart constraints and provider-specific behavior.

---

## 5. VIDEO PROCESSING AND TRANSCODING

Implement a distributed processing pipeline using FFmpeg and FFprobe.

Support:

- Metadata extraction
- Codec detection
- Resolution detection
- Duration detection
- Audio stream inspection
- Video validation
- Thumbnail generation
- Video transcoding
- Adaptive bitrate rendition generation
- HLS packaging
- Optional MPEG-DASH packaging
- Processing status inspection
- Failure recovery

### Example rendition ladder

Treat the following as an illustrative configuration rather than a universal encoding standard.

| Resolution | Example Video Bitrate |
|------------|----------------------:|
| 240p       |              400 kbps |
| 360p       |              800 kbps |
| 480p       |              1.5 Mbps |
| 720p       |                3 Mbps |
| 1080p      |                5 Mbps |
| 1440p      |               10 Mbps |
| 2160p / 4K |               18 Mbps |

Actual encoding settings must consider:

- Source quality
- Frame rate
- Codec
- Content complexity
- Device compatibility
- Audio bitrate
- Target quality
- Encoding cost
- Bandwidth efficiency

Do not upscale low-resolution source material merely to fill the rendition ladder.

---

## 6. VIDEO LIFECYCLE

Define states such as:

- DRAFT
- UPLOADING
- UPLOADED
- VALIDATING
- QUEUED
- TRANSCODING
- PACKAGING
- VERIFYING
- READY
- PUBLISHED
- FAILED
- BLOCKED
- DELETED

Define valid transitions and ownership of state changes.

Separate **media processing readiness** from **public visibility**.

A video should not become playable until required artifacts are verified.

Support retrying failed processing jobs without publishing partial or corrupt output.

---

## 7. VIDEO CATALOG

Implement:

- Create video metadata
- Update title/description
- Set category
- Add tags
- Set visibility
- Publish/unpublish
- Retrieve video details
- List creator videos
- List published videos
- Filter by category
- Sort by publication date
- Cursor-based pagination

Maintain video ownership and authorization rules.

---

## 8. VIDEO SEARCH AND DISCOVERY

Implement:

- Search by title
- Search by description
- Search by tags
- Filter by category
- Filter by creator
- Filter by duration
- Sort by relevance
- Sort by publication date

Start with PostgreSQL full-text search when sufficient.

Evaluate OpenSearch or Elasticsearch only when search requirements justify additional infrastructure.

Implement a functional baseline search experience.

---

## 9. VIDEO PLAYBACK

Support:

- HLS playback
- Adaptive bitrate selection
- Playback resume
- Seek
- Pause/resume
- Playback session creation
- Authorized manifest access
- Secure media delivery
- Playback analytics

Use a functional web video player with HLS support.

Explain browser-native HLS compatibility versus JavaScript-based playback libraries.

Do not build a custom video decoder or adaptive bitrate algorithm if a maintained player library satisfies the
requirement.

---

## 10. WATCH HISTORY

Implement:

- Record playback progress
- Continue watching
- Last watched timestamp
- Recently watched videos
- Completed videos
- Resume position
- Cross-device synchronization

Define update intervals and storage semantics.

Avoid persisting a database transaction on every video frame or every minor playback-time change.

Support idempotent and monotonic progress updates where appropriate.

---

## 11. USER ENGAGEMENT

Implement:

- Like/unlike
- Comment
- Delete comment
- View count
- Creator follow/subscribe as an optional extension
- Watchlist/favorites

Ensure:

- Duplicate likes are prevented.
- Engagement updates are concurrency-safe.
- View-count semantics are explicitly defined.
- Event retries do not permanently inflate counters.
- Removed videos are handled consistently.

---

# PART C — NON-FUNCTIONAL REQUIREMENTS

## 12. SCALE AND PERFORMANCE TARGETS

Treat these as architectural goals requiring benchmarking.

| Dimension                      | Target                                         |
|--------------------------------|------------------------------------------------|
| Registered users               | 100 million–1 billion                          |
| Daily active users             | 50 million–100 million                         |
| Concurrent viewers             | 10 million                                     |
| Video catalog                  | 100 million+ videos                            |
| New uploads/day                | 1 million                                      |
| Playback starts                | 1 million/minute as a design scenario          |
| Metadata API throughput        | 100,000 requests/second                        |
| Playback authorization latency | p99 below 100 ms within a region               |
| Playback start time            | p95 below 2 seconds under specified conditions |
| Rebuffering ratio              | Defined and measured QoE target                |
| Video processing               | Horizontally scalable worker pool              |
| Availability                   | 99.99% target                                  |
| Durability                     | Defined per asset and metadata class           |
| Deployment                     | Multi-AZ with multi-region extension           |

Do not confuse:

- Concurrent video sessions
- HTTP segment requests
- API request throughput
- CDN edge throughput
- Origin requests
- Transcoding jobs/second
- Video storage capacity

Define SLO measurement boundaries, workload distributions, and assumptions.

---

# PART D — HIGH-LEVEL DESIGN

## 13. ARCHITECTURE ALTERNATIVES

Compare:

### Option A — Modular Monolith + Background Workers

One Spring Boot application handles metadata, authorization, catalog, uploads, and playback APIs.

Separate workers perform CPU-intensive media processing.

### Option B — Domain-Oriented Services

Potential services:

- User Service
- Video Metadata Service
- Upload Service
- Processing Orchestrator
- Transcoding Workers
- Playback Service
- Recommendation Service
- Analytics Service

### Option C — Globally Distributed Platform

Regional services, multi-region storage, global CDN delivery, distributed metadata, and regional processing
infrastructure.

Evaluate:

- Engineering complexity
- Deployment independence
- Operational cost
- Throughput
- Failure isolation
- Media bandwidth
- Scaling
- Data consistency

**Recommend the simplest architecture that can implement the end-to-end workflow correctly while preserving a credible
scale-out path.**

Do not introduce independent services without a concrete scaling or operational requirement.

---

## 14. REFERENCE ARCHITECTURE

Consider:

1. Client Application
2. API Gateway
3. Authentication/Authorization
4. Video Catalog Module
5. Upload Orchestrator
6. S3-Compatible Object Storage
7. Video Processing Orchestrator
8. Kafka Event Backbone
9. Transcoding Worker Pool
10. FFmpeg/FFprobe
11. Packaging Pipeline
12. Thumbnail Generator
13. PostgreSQL
14. Redis
15. CDN
16. Playback Authorization Service
17. Watch History Module
18. Recommendation Engine
19. Analytics Pipeline
20. Monitoring/Tracing Infrastructure

Provide Mermaid architecture diagrams showing:

- Upload/control plane
- Object-storage data plane
- Video processing pipeline
- Playback request path
- CDN/origin delivery
- Analytics flow
- Failure recovery

Explicitly distinguish control-plane traffic from video segment delivery.

---

# PART E — CAPACITY PLANNING

## 15. STORAGE AND BANDWIDTH ESTIMATION

Calculate:

1. Uploads per second
2. Average video size
3. Daily source-video storage growth
4. Encoded rendition storage multiplier
5. Thumbnail storage
6. Annual storage growth
7. Object count
8. Processing CPU-hours
9. Transcoding concurrency
10. Metadata database growth
11. Kafka event throughput
12. CDN bandwidth
13. Origin bandwidth
14. Cache hit ratio
15. Cross-region replication cost

Use formulas.

Example:

`Streaming egress = concurrent viewers × average delivered bitrate`

Calculate bandwidth scenarios for:

- 100,000 viewers
- 1 million viewers
- 10 million viewers

Evaluate average delivered bitrates of 2 Mbps, 5 Mbps, and 10 Mbps.

Distinguish decimal Mbps/Gbps/Tbps and account for protocol overhead when needed.

Explain the impact of CDN hit ratio on origin load.

Do not invent infrastructure performance measurements.

---

# PART F — LOW-LEVEL DESIGN

## 16. DOMAIN MODEL

Design focused concepts such as:

- Video
- VideoId
- VideoAsset
- VideoVersion
- UploadSession
- UploadPart
- ProcessingJob
- ProcessingAttempt
- Rendition
- MediaSegment
- Manifest
- Thumbnail
- PlaybackSession
- WatchProgress
- VideoVisibility
- VideoStatus
- VideoLike
- Comment
- RecommendationCandidate
- OutboxEvent

Separate domain, application, API, and infrastructure layers.

Use Java 21 records for immutable DTOs and value objects where suitable.

Provide:

- UML class diagram
- Sequence diagrams
- State diagrams
- Repository interfaces
- Transaction boundaries
- Error handling strategy

---

## 17. POSTGRESQL DATABASE DESIGN

Design tables for:

- users
- creators
- videos
- video_assets
- upload_sessions
- upload_parts
- processing_jobs
- processing_attempts
- renditions
- video_thumbnails
- playback_sessions
- watch_progress
- video_likes
- comments
- outbox_events

Create only the tables needed by the implemented features.

Define:

- Primary keys
- Unique constraints
- Foreign keys
- Indexes
- Optimistic locking
- Audit timestamps
- Retention
- Partitioning considerations
- Soft deletion
- State transition invariants

Implement Flyway migrations.

### Correctness requirements

- One authoritative current video version.
- No duplicate publication from retried jobs.
- Immutable published media artifacts.
- Correct ownership enforcement.
- Safe concurrent updates.
- Durable upload-session state.
- Recoverable processing lifecycle.
- No partial manifest publication.

---

# PART G — VIDEO UPLOAD ARCHITECTURE

## 18. RESUMABLE MULTIPART UPLOADS

Implement a workflow such as:

1. Create upload session.
2. Validate creator quota.
3. Reserve expected storage.
4. Initiate S3 multipart upload.
5. Generate scoped upload-part URLs.
6. Client uploads parts directly to storage.
7. Client submits completion request.
8. Verify part metadata and checksums.
9. Complete multipart upload.
10. Verify final object.
11. Commit video metadata and processing request.
12. Release/commit quota reservation.
13. Return stable upload status.

Address:

- Interrupted uploads
- Duplicate part submission
- Expired URLs
- Duplicate completion requests
- Multipart completion ambiguity
- Aborted uploads
- Orphaned objects
- Quota exhaustion
- Concurrent uploads
- Storage-provider errors

Do not assume database transactions can atomically commit with object-storage operations.

Implement reconciliation for partial failures.

---

# PART H — DISTRIBUTED VIDEO PROCESSING

## 19. TRANSCODING ARCHITECTURE

Design:

- Processing job creation
- Durable job queue
- Worker assignment
- Processing attempt tracking
- Worker heartbeat
- Job timeout
- Bounded retry
- Dead-letter handling
- Processing result verification
- Output publication

Compare Kafka-based orchestration, database-backed job queues, and specialized batch/job schedulers.

Choose the approach appropriate to the workload.

### Important considerations

Video transcoding jobs may run for minutes or hours.

Do not hold Kafka consumer threads or short broker leases hostage for the entire processing duration without an explicit
safe design.

Prefer durable job state with claim/lease/heartbeat semantics and idempotent worker retries.

Use object storage for source and output artifacts.

---

## 20. FFMPEG EXECUTION

Implement safe FFmpeg and FFprobe integration.

Requirements:

- Validated executable path
- Argument-list process execution
- No shell-string concatenation
- Process timeouts
- Worker cancellation
- Resource limits
- Temporary disk limits
- CPU constraints
- Memory constraints
- Exit-code handling
- Bounded process output capture
- Progress reporting
- Cleanup

Do not execute arbitrary client-supplied FFmpeg arguments.

Define processing profiles server-side.

Treat uploaded media as untrusted input.

---

## 21. PROCESSING IDEMPOTENCY

Handle this scenario:

1. Worker A starts processing a video.
2. Worker A finishes encoding.
3. Worker A crashes before recording completion.
4. Worker B retries the job.
5. Both attempts may have produced artifacts.

Prevent inconsistent publication.

Use:

- Processing attempt IDs
- Immutable attempt-specific output paths
- Atomic metadata transitions
- Conditional publication
- Cleanup of superseded outputs
- Reconciliation

Do not overwrite a published output in place.

---

# PART I — HLS AND MPEG-DASH

## 22. ADAPTIVE BITRATE STREAMING

Explain and implement HLS, with MPEG-DASH as an optional supported format.

Cover:

- Master playlists
- Variant playlists
- Media segments
- Segmentation duration
- Keyframe alignment
- Codec compatibility
- Audio tracks
- Captions/subtitles
- Bitrate ladder
- CDN caching

Provide a working output example:

```text
videos/{videoId}/{version}/
├── master.m3u8
├── 360p/
│   ├── index.m3u8
│   ├── segment_000.ts
│   └── ...
├── 720p/
│   ├── index.m3u8
│   ├── segment_000.ts
│   └── ...
├── 1080p/
│   ├── index.m3u8
│   ├── segment_000.ts
│   └── ...
└── thumbnails/
    └── cover.jpg
```

Choose MPEG-TS or CMAF/fMP4 packaging based on the supported players and codec requirements.

Ensure manifest references match actual stored objects.

Verify stream compatibility using FFprobe and playback tests.

---

## 23. VIDEO QUALITY AND ADAPTIVE PLAYBACK

Explain:

- Startup bitrate selection
- Network throughput estimation
- Buffer-based adaptation
- Rebuffering
- Segment duration
- Rendition switching
- Player errors
- Playback recovery

Use a maintained HLS player library rather than implementing adaptive bitrate logic from scratch.

Define metrics for:

- Video startup time
- Rebuffer frequency
- Rebuffer duration
- Average delivered bitrate
- Playback failures
- Quality switches

---

# PART J — CDN AND CONTENT DELIVERY

## 24. CDN ARCHITECTURE

Design:

- Origin object storage
- CDN edge caches
- Regional distribution
- Cache keys
- Cache-control headers
- Manifest caching
- Segment caching
- Origin shielding
- Signed URLs/cookies where required

Explain:

- Why video segments should be served through a CDN.
- How immutable video versions simplify caching.
- How CDN caching reduces origin traffic.
- How hot videos are served efficiently.
- Why query-string authentication can affect cache efficiency.

Provide a CDN-ready local implementation and documented cloud deployment integration.

Do not pretend Docker Compose provides a globally distributed CDN.

---

## 25. PLAYBACK AUTHORIZATION

Implement:

- Video visibility checks
- Ownership and access rules
- Playback session authorization
- Expiring playback credentials
- Secure manifest access
- Private media protection

For subscription-based streaming, evaluate entitlement verification.

Explain the difference between:

- API authorization
- Signed CDN access
- Object-storage access
- Digital Rights Management (DRM)

Do not claim tokenized URLs alone provide DRM protection.

---

# PART K — DIGITAL RIGHTS MANAGEMENT

## 26. CONTENT PROTECTION

Evaluate:

- Signed URLs
- Signed cookies
- AES-encrypted HLS
- Widevine
- PlayReady
- FairPlay
- License services

For the baseline implementation, provide authenticated access to private content.

Treat studio-grade multi-DRM integration as a separately scoped advanced capability requiring appropriate DRM providers,
licenses, packaging, and compatible players.

Do not build proprietary cryptography or claim Netflix-equivalent DRM based solely on TLS.

---

# PART L — WATCH HISTORY AND ANALYTICS

## 27. WATCH PROGRESS

Implement:

- Start playback
- Update progress
- Resume playback
- Mark complete
- Continue watching
- Cross-device synchronization

Address:

- Duplicate progress updates
- Out-of-order updates
- Device concurrency
- Time-based throttling
- Idempotency
- Privacy

Define whether the most recent session wins or progress is merged according to a documented policy.

---

## 28. VIEW COUNT AND ENGAGEMENT ANALYTICS

Define what constitutes a valid view.

Evaluate:

- Minimum watch duration
- Duplicate playback events
- Bot detection
- Idempotency keys
- Aggregation windows
- Eventual consistency

Use asynchronous event processing where justified.

Avoid increasing authoritative view counts for every playback heartbeat.

Implement:

- PlaybackStarted
- PlaybackProgressed
- PlaybackCompleted
- PlaybackFailed

Design schema versions and consumer deduplication.

---

# PART M — RECOMMENDATION SYSTEM

## 29. RECOMMENDATION ENGINE

Implement a practical baseline recommendation system.

Start with:

- Trending videos
- Popular videos
- Category-based recommendations
- Recently watched categories
- Creator affinity
- Content similarity from metadata

Compare:

1. Popularity-based ranking
2. Content-based filtering
3. Collaborative filtering
4. Embedding-based retrieval
5. Two-stage candidate generation and ranking

Start with an explainable ranking model.

Define normalization and weighted scoring.

Avoid introducing a full machine-learning platform unless the product requirements justify it.

### Recommendation concerns

- Cold start
- Popularity bias
- Diversity
- Freshness
- Feedback loops
- Abuse
- Privacy
- Ranking latency

Implement working recommendation APIs with tests.

---

# PART N — SEARCH AND DISCOVERY

## 30. SEARCH ARCHITECTURE

Start with PostgreSQL full-text search.

Evaluate dedicated search infrastructure for:

- Fuzzy matching
- Autocomplete
- Multilingual search
- Large catalog indexing
- Complex relevance ranking

If asynchronous indexing is used, define:

- Indexing lag
- Duplicate events
- Deleted video removal
- Rebuild strategy
- Search consistency

Ensure unauthorized videos cannot be exposed through stale search results.

---

# PART O — CACHING

## 31. REDIS CACHING

Consider caches for:

- Video metadata
- Playback authorization hints
- Trending lists
- Recommendation candidates
- Creator profiles
- Engagement aggregates
- Catalog pages

Define:

- Cache keys
- TTL
- Eviction
- Invalidation
- Negative caching
- Hot-key protection
- Cache stampede prevention
- Cache outage fallback

Do not use cached metadata to bypass current authorization requirements.

Treat Redis as an acceleration layer rather than the durable source of video metadata.

---

# PART P — KAFKA AND EVENT PROCESSING

## 32. EVENT-DRIVEN ARCHITECTURE

Define events such as:

- VideoUploadCompleted
- VideoProcessingRequested
- VideoProcessingStarted
- VideoProcessingCompleted
- VideoProcessingFailed
- VideoPublished
- VideoDeleted
- PlaybackStarted
- PlaybackCompleted
- VideoLiked

Define:

- Event ID
- Aggregate ID
- Aggregate version
- Schema version
- Timestamp
- Correlation ID

Implement transactional outbox where database commits must reliably trigger asynchronous events.

Address:

- Duplicate delivery
- Out-of-order processing
- Retry policies
- Dead-letter handling
- Consumer lag
- Backpressure
- Replay
- Schema compatibility

Do not claim end-to-end exactly-once processing based only on Kafka transactions.

---

# PART Q — MULTI-REGION ARCHITECTURE

## 33. GLOBAL VIDEO DELIVERY

Design a multi-region extension.

Analyze:

- Geo-based traffic routing
- Regional API services
- Regional metadata replicas
- Cross-region object replication
- Global CDN
- Regional transcoding
- Data residency
- Disaster recovery
- Content publishing propagation
- Regional outages

Compare:

1. Single-region multi-AZ
2. Active-passive multi-region
3. Active-active multi-region

Define RPO/RTO and consistency behavior.

Do not assume object replication is instantly consistent across all regions.

---

# PART R — SECURITY

## 34. SECURITY REQUIREMENTS

Implement or document:

1. OAuth2/OIDC authentication
2. Spring Security authorization
3. Creator ownership checks
4. Video visibility enforcement
5. Secure upload credentials
6. Object-storage access restrictions
7. HTTPS/TLS
8. Secrets management
9. Upload quotas
10. Upload rate limiting
11. File type validation
12. Malware scanning integration
13. Safe FFmpeg execution
14. Worker isolation
15. Network egress restrictions
16. CDN access controls
17. Audit logging
18. Content moderation integration points
19. Data retention and deletion
20. Privacy-aware analytics
21. Abuse protection
22. Optional subscription entitlements

Treat uploaded media as untrusted input.

Avoid exposing raw object-storage buckets publicly.

Never store production secrets in source control.

---

# PART S — TECHNOLOGY STACK

## 35. RECOMMENDED STACK

Use:

| Component            | Technology                                      |
|----------------------|-------------------------------------------------|
| Language             | Java 21                                         |
| Framework            | Spring Boot, verified compatible stable release |
| Build                | Maven                                           |
| REST APIs            | Spring Web MVC                                  |
| Security             | Spring Security                                 |
| Database             | PostgreSQL                                      |
| Database migrations  | Flyway                                          |
| Data access          | Spring Data JPA/JDBC as justified               |
| Cache                | Redis / Lettuce                                 |
| Messaging            | Apache Kafka                                    |
| Object storage       | Amazon S3-compatible API                        |
| Local object storage | MinIO or equivalent                             |
| Video processing     | FFmpeg / FFprobe                                |
| Streaming            | HLS, optional MPEG-DASH                         |
| Web player           | Maintained HLS-compatible JavaScript player     |
| Observability        | Micrometer / OpenTelemetry                      |
| Unit testing         | JUnit 5 / Mockito                               |
| Integration testing  | Testcontainers                                  |
| Performance testing  | k6 / Gatling                                    |
| Containers           | Docker                                          |
| Deployment           | Kubernetes                                      |
| Monitoring           | Prometheus / Grafana                            |

Use the AWS SDK for Java v2 for S3-compatible operations unless a justified alternative is selected.

Verify exact dependency versions and compatibility against official documentation.

Avoid unnecessary WebFlux adoption if Spring MVC and direct object storage transfers meet the requirements.

---

# PART T — SOURCE CODE ORGANIZATION

## 36. PROJECT STRUCTURE

Use a focused project structure.

```text
video-streaming-platform/
├── pom.xml
├── README.md
├── docs/
│   ├── architecture.md
│   ├── media-pipeline.md
│   ├── streaming-protocol.md
│   ├── capacity-planning.md
│   ├── security.md
│   ├── runbook.md
│   └── decisions/
├── src/
│   ├── main/
│   │   ├── java/com/example/streaming/
│   │   │   ├── identity/
│   │   │   ├── catalog/
│   │   │   ├── upload/
│   │   │   ├── processing/
│   │   │   ├── playback/
│   │   │   ├── recommendation/
│   │   │   ├── analytics/
│   │   │   ├── engagement/
│   │   │   ├── security/
│   │   │   └── shared/
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   └── test/
│       └── java/
├── transcoding-worker/
├── web-player/
├── benchmarks/
├── docker/
├── helm/
└── .github/workflows/
```

Use separate Maven modules or deployables when there are genuine independent scaling needs, particularly for transcoding
workers.

Keep dependencies acyclic and boundaries clear.

---

# PART U — REST API DESIGN

## 37. REQUIRED APIS

Implement the following.

### Video Management

- `POST /api/v1/videos`
- `GET /api/v1/videos/{videoId}`
- `PATCH /api/v1/videos/{videoId}`
- `DELETE /api/v1/videos/{videoId}`
- `GET /api/v1/videos`

### Upload

- `POST /api/v1/videos/uploads`
- `GET /api/v1/videos/uploads/{uploadId}`
- `POST /api/v1/videos/uploads/{uploadId}/parts`
- `POST /api/v1/videos/uploads/{uploadId}/complete`
- `POST /api/v1/videos/uploads/{uploadId}/abort`

### Processing

- `GET /api/v1/videos/{videoId}/processing-status`
- `POST /api/v1/videos/{videoId}/reprocess`

### Playback

- `POST /api/v1/videos/{videoId}/playback-sessions`
- `GET /api/v1/videos/{videoId}/playback`
- `PUT /api/v1/videos/{videoId}/watch-progress`
- `GET /api/v1/users/me/continue-watching`

### Search and Discovery

- `GET /api/v1/search/videos`
- `GET /api/v1/videos/trending`
- `GET /api/v1/videos/recommendations`

### Engagement

- `PUT /api/v1/videos/{videoId}/likes/me`
- `DELETE /api/v1/videos/{videoId}/likes/me`
- `POST /api/v1/videos/{videoId}/comments`
- `GET /api/v1/videos/{videoId}/comments`

Provide:

- Request/response JSON
- HTTP status codes
- Validation
- Authentication
- Authorization
- Pagination
- Idempotency behavior
- Error contracts
- OpenAPI specification

Use RFC 9457 Problem Details where applicable.

---

# PART V — CONCURRENCY AND RELIABILITY

## 38. DISTRIBUTED TRANSACTION BOUNDARIES

Explicitly address:

- PostgreSQL transactions
- S3 multipart operations
- Kafka publication
- FFmpeg processing
- Manifest publication
- CDN cache propagation

These operations cannot participate in one simple atomic database transaction.

Design a recoverable state-machine workflow.

Use:

- Idempotency keys
- Transactional outbox
- Conditional state transitions
- Processing leases
- Attempt-specific output paths
- Reconciliation jobs
- Bounded retries
- Compensation

Ensure successful acknowledgment has a precise durability meaning.

---

## 39. REQUIRED FAILURE SCENARIOS

Analyze and test at least these 30 scenarios:

1. Upload interrupted halfway through.
2. Multipart upload completes but database update fails.
3. Upload completion response is lost.
4. Two clients complete the same upload concurrently.
5. Object storage becomes temporarily unavailable.
6. Video processing event is published twice.
7. Kafka becomes unavailable.
8. Transcoding worker crashes.
9. FFmpeg process hangs.
10. FFmpeg exits with an error.
11. Worker runs out of temporary disk.
12. Worker runs out of memory.
13. Processing lease expires during a long transcode.
14. Two workers process the same video concurrently.
15. Worker completes processing but metadata commit fails.
16. Manifest is published before all segments exist.
17. A required rendition fails validation.
18. Thumbnail generation fails.
19. CDN caches stale manifests.
20. Video visibility changes while cached at the edge.
21. Playback credentials expire during streaming.
22. A video becomes globally viral.
23. Redis becomes unavailable.
24. PostgreSQL primary fails.
25. Recommendation processing falls behind.
26. Playback events are delivered twice.
27. View counts drift because of duplicate events.
28. A regional outage interrupts playback authorization.
29. A schema migration runs during rolling deployment.
30. Video deletion races with background transcoding or garbage collection.

For each provide:

- Failure mechanism
- Data integrity or security risk
- User-visible impact
- Detection signals
- Prevention
- Recovery
- Automated test approach
- Residual risk

---

# PART W — TEST STRATEGY

## 40. UNIT TESTS

Cover:

- Upload state transitions
- Video lifecycle transitions
- Ownership rules
- Visibility rules
- Idempotency
- Retry policies
- Rendition selection
- Processing job claims
- Manifest metadata
- Watch progress merging
- Engagement counters
- Recommendation scoring

## 41. INTEGRATION TESTS

Use Testcontainers and local dependencies where appropriate.

Test:

- PostgreSQL migrations
- S3-compatible uploads
- Multipart completion
- Kafka event publication
- Transactional outbox
- FFmpeg processing
- HLS output validation
- Playback authorization
- Cache invalidation
- Watch history persistence

## 42. MEDIA PIPELINE TESTS

Use small, legally usable synthetic video fixtures.

Verify:

- FFprobe metadata
- Multiple rendition generation
- Segment existence
- Manifest correctness
- Codec compatibility
- Audio/video synchronization
- Playback startup
- Seek behavior
- Processing retries
- Partial output cleanup

Generate test media locally using FFmpeg where appropriate.

Do not require copyrighted production content for automated testing.

## 43. CONCURRENCY TESTS

Verify:

- Concurrent upload completion
- Duplicate processing events
- Multiple worker job claims
- Concurrent publication
- Video update/delete races
- Idempotent engagement
- Watch-progress updates from multiple devices

## 44. CHAOS AND FAILURE TESTS

Simulate:

- Object storage outage
- Kafka outage
- Database timeout
- Worker termination
- FFmpeg process timeout
- Disk exhaustion
- Network interruption
- Lost acknowledgment
- Cache outage
- CDN/origin failure simulation

Distinguish executed checks from proposed tests.

---

# PART X — PERFORMANCE ENGINEERING

## 45. LOAD TESTING

Create representative load tests for:

### Metadata APIs

- 1,000 RPS
- 10,000 RPS
- 100,000 RPS

### Video ingestion

- Concurrent small uploads
- Concurrent large uploads
- Multipart upload completion
- Upload session creation

### Video processing

- 10 concurrent jobs
- 100 concurrent jobs
- 1,000 concurrent jobs as a capacity scenario

### Playback

- 1,000 concurrent viewers
- 10,000 concurrent viewers
- 100,000 concurrent viewers
- 1 million concurrent viewers as a scale target

Test segment delivery separately from Spring Boot API requests.

Use a CDN or representative cache/origin setup for realistic streaming benchmarks.

### Measure

- API throughput
- p50/p95/p99 latency
- Playback startup time
- Rebuffering ratio
- Segment download latency
- Delivered bitrate
- CDN hit ratio
- Origin bandwidth
- Transcoding time
- Encoding throughput
- Processing queue depth
- Worker CPU/GPU utilization where applicable
- Database load
- Redis latency
- Kafka lag
- JVM CPU/heap/GC
- Error rates

Never invent performance measurements.

---

# PART Y — PRODUCTION DEPLOYMENT

## 46. DOCKER AND KUBERNETES

Provide:

- Multi-stage Dockerfiles
- Backend image
- Transcoding worker image
- Web player deployment
- Docker Compose
- PostgreSQL
- Redis
- Kafka where implemented
- MinIO or equivalent
- Kubernetes manifests or Helm charts
- API deployment
- Worker deployment
- Resource requests/limits
- HPA
- PodDisruptionBudget
- Readiness/liveness probes
- Graceful shutdown
- Rolling deployment
- CI/CD workflow

Keep transcoding worker resource requirements separate from metadata/API services.

Provide local development instructions that can process and play a small sample video without requiring a cloud account.

---

# PART Z — OBSERVABILITY AND SRE

## 47. METRICS AND ALERTING

Expose:

- Upload session count
- Upload failure rate
- Uploaded bytes
- Processing queue depth
- Transcoding duration
- Processing failure count
- Retry count
- Worker saturation
- Video publication delay
- Playback session creation latency
- Playback authorization failures
- CDN cache hit ratio where available
- Origin errors
- Playback errors
- Watch event lag
- Kafka consumer lag
- Database latency
- Redis latency

Provide:

- Prometheus configuration
- Grafana dashboards
- OpenTelemetry traces
- Structured logs
- SLO definitions
- Alert thresholds
- Operational runbooks

Avoid high-cardinality video IDs or user IDs in metrics labels.

Never log signed playback URLs or sensitive credentials.

---

# PART AA — IMPLEMENTATION WORKFLOW

## 48. DEVELOPMENT PHASES

Execute the project incrementally.

**Phase 1 — Requirements and Architecture**

Define product scope, media formats, durability model, processing architecture, playback model, scale assumptions, and
SLOs.

**Phase 2 — Core Metadata and Catalog**

Implement users/creators, video metadata, state machines, migrations, CRUD APIs, ownership, and visibility.

**Phase 3 — Upload Pipeline**

Implement resumable multipart uploads, direct object-storage transfer, completion verification, quotas, and
reconciliation.

**Phase 4 — Video Processing**

Implement durable processing jobs, FFmpeg/FFprobe integration, multiple renditions, thumbnails, HLS packaging, and
artifact verification.

**Phase 5 — Playback**

Implement playback authorization, manifest access, secure segment delivery, and a functional HLS web player.

**Phase 6 — Search, Watch History, and Engagement**

Implement search, progress tracking, continue watching, likes, comments, and counters.

**Phase 7 — Recommendations and Analytics**

Implement baseline ranking, playback events, asynchronous aggregation, and reporting.

**Phase 8 — Reliability and Security**

Add bounded retries, timeouts, idempotency, outbox, background recovery, media validation, privacy, and monitoring.

**Phase 9 — Deployment**

Implement Docker Compose, Kubernetes/Helm, CI/CD, and operational configuration.

**Phase 10 — Testing and Performance**

Run automated tests, execute representative media pipeline tests, benchmark available workloads, and fix significant
defects.

**Phase 11 — Final Architecture Review**

Review scalability, data integrity, consistency, security, video quality, operational cost, and production risks.

For each phase:

- Implement actual files.
- Keep the project buildable.
- Run appropriate checks.
- Fix failures before moving forward.
- Preserve unrelated work.
- Document significant design decisions.
- Clearly state verification results and unresolved risks.

If repository and execution tools are available, create files and run the project directly.

If unavailable, provide complete source files, file paths, and commands without claiming execution.

---

# PART AB — REQUIRED DELIVERABLES

## 49. COMPLETE PROJECT OUTPUTS

Deliver:

### Deliverable 1 — Requirements

- Functional requirements
- Non-functional requirements
- Assumptions
- SLOs
- Acceptance criteria

### Deliverable 2 — High-Level Design

- Architecture diagrams
- Component responsibilities
- Data flows
- Processing pipeline
- CDN architecture
- Capacity planning
- Multi-region strategy

### Deliverable 3 — Low-Level Design

- UML class diagram
- Sequence diagrams
- State machines
- Domain model
- Database schema
- API contracts
- Job processing protocol

### Deliverable 4 — Complete Backend

- Java 21 Spring Boot source
- Video metadata
- Upload orchestration
- Multipart upload
- Storage integration
- Video processing orchestration
- FFmpeg workers
- HLS packaging
- Playback authorization
- Watch history
- Search
- Engagement
- Recommendations
- Event processing

### Deliverable 5 — Functional Web Player

- Video catalog UI
- Video detail page
- HLS playback
- Quality switching through the player
- Seek and resume
- Watch progress
- Playback error handling

### Deliverable 6 — Testing

- Unit tests
- Integration tests
- Media pipeline tests
- Concurrency tests
- Security tests
- Failure recovery tests

### Deliverable 7 — Infrastructure

- Dockerfiles
- Docker Compose
- Kubernetes/Helm
- CI/CD
- Observability configuration

### Deliverable 8 — Performance

- Load-test scripts
- Media-processing benchmarks
- Playback benchmarks
- Actual measured results where possible
- Scalability estimates
- Bottleneck analysis

### Deliverable 9 — Documentation

- README
- Architecture Decision Records
- API specification
- Upload protocol
- Media pipeline
- Streaming format details
- Deployment guide
- Security guide
- Operational runbook
- Known limitations

---

# PART AC — MANDATORY SYSTEM DESIGN REVIEW

## 50. ARCHITECTURE QUESTIONS

Answer these questions as part of the final review.

1. How would you design Netflix for 100 million users?
2. How would you design YouTube for billions of daily video views?
3. Why separate video metadata from media storage?
4. How do resumable multipart uploads work?
5. Why should clients upload directly to object storage?
6. How do you process a 10 GB video safely?
7. How do you distribute transcoding jobs?
8. How do you prevent duplicate processing?
9. What happens when a transcoding worker crashes?
10. How does FFmpeg generate multiple renditions?
11. What is HLS?
12. How does MPEG-DASH differ from HLS?
13. How does adaptive bitrate streaming work?
14. Why is keyframe alignment important?
15. How do video manifests and segments work?
16. Why is a CDN essential for large-scale streaming?
17. How do you prevent origin overload?
18. How do you handle a globally viral video?
19. How do you secure private video playback?
20. What is the difference between signed URLs and DRM?
21. How do you manage video versions?
22. How do you prevent partially generated media from being published?
23. How do you implement watch history across devices?
24. How do you count video views accurately?
25. How do you prevent duplicate analytics events?
26. How do recommendation systems work?
27. How would you implement basic personalized recommendations?
28. How do you scale video search?
29. How do you maintain catalog consistency?
30. How do you handle content deletion across CDN caches?
31. How do you manage multi-region object replication?
32. What happens during regional outages?
33. How do you optimize transcoding cost?
34. How do you benchmark playback quality?
35. How do you support 10 million concurrent viewers?
36. How do you calculate streaming bandwidth?
37. How do you prevent FFmpeg resource exhaustion?
38. How do you handle processing queue backlog?
39. How do you guarantee safe upload-to-publication transitions?
40. What must be verified before production deployment?

Explain architectural reasoning, concurrency, data integrity, scalability, failure behavior, and operational trade-offs.

---

# DEFINITION OF DONE

## 51. ACCEPTANCE CRITERIA

The implementation is complete only when:

- Java 21 application builds successfully.
- PostgreSQL migrations execute.
- Creator/video management works.
- Upload sessions are durable.
- Multipart upload completion works.
- Source media is stored in S3-compatible storage.
- Video processing jobs are recoverable.
- FFmpeg produces verified output.
- At least two playable video renditions are generated for suitable source media.
- HLS manifests and segments are valid.
- A video is published only after required artifacts exist.
- Authorized playback works.
- A functional web player can play generated media.
- Watch history and resume functionality work.
- Basic search and discovery work.
- Likes/comments function correctly.
- Baseline recommendations return eligible videos.
- Asynchronous events are idempotently processed where implemented.
- Automated tests cover meaningful failure paths.
- Docker Compose supports a complete upload-to-playback workflow.
- Kubernetes deployment configuration is provided.
- Metrics and health checks are functional.
- Load tests are reproducible.
- Performance targets are measured or marked unverified.
- Known limitations and production risks are documented.

Passing unit tests or successfully playing one sample video is insufficient evidence of global-scale production
readiness.

---

# FINAL EXECUTION INSTRUCTIONS

Build a **real, production-oriented, distributed video streaming platform** using Java 21 and Spring Boot.

Prioritize:

1. **Media integrity:** Uploaded and published video artifacts are complete and verifiable.
2. **Correctness:** Durable metadata, safe state transitions, and idempotent processing.
3. **Playback reliability:** Compatible manifests, segments, and functional adaptive streaming.
4. **Scalability:** Direct object-storage transfers, independent transcoding workers, and CDN-based delivery.
5. **Security:** Protected uploads, private playback authorization, and safe media processing.
6. **Fault tolerance:** Recoverable uploads, jobs, and asynchronous workflows.
7. **Performance:** Efficient metadata APIs, media processing, and playback startup.
8. **Maintainability:** Focused Java 21 modules and clear architectural boundaries.
9. **Observability:** Diagnosable processing failures and playback issues.
10. **Reproducibility:** Runnable source code, local infrastructure, tests, and benchmarks.

Begin with a concise architectural assessment.

Select the simplest implementation capable of supporting a full upload-to-playback workflow.

Then complete each implementation phase with actual source code, tests, and infrastructure.

At completion report:

- Implemented features
- Architecture decisions and trade-offs
- Source structure and important files
- Exact build and run commands
- Tests actually executed
- Benchmark results actually measured
- Known limitations
- Remaining production risks

**Do not stop after HLD or LLD. Deliver the complete video streaming system end to end, including backend, video
processing workers, playback client, tests, deployment infrastructure, and operational documentation.**