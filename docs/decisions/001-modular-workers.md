# ADR 001: Modular control plane and durable PostgreSQL workers

Status: accepted for baseline. Keep metadata/control transactions cohesive; scale FFmpeg in independent containers.
PostgreSQL rows provide long-lived processing state, claims and fenced publication. Do not retain a Kafka consumer lease
through a transcode. Revisit a broker when measured event fanout or analytics rate exceeds this architecture. Redis is
optional acceleration; correctness never depends on cache. Source and encoded bytes stay in private S3; Nginx origin
streams them. Cloud CDN and edge-signed authorization replace local per-segment API checks at scale.

Spring Boot3.5.16 follows adjacent projects
and [official Java compatibility](https://docs.spring.io/spring-boot/3.5/system-requirements.html). Java21
compilation/runtime verified in local checks. [hls.js1.6.13](https://github.com/video-dev/hls.js/releases/tag/v1.6.13)
provides browser ABR rather than custom playback logic.
