# Capacity estimates — assumptions, not measurements

Decimal units unless MiB/GiB specified. Scenario: 1,000,000 uploads/day, average source 500 MB, mean 10 minutes,
rendition sum 10.7 Mbps (including illustrative audio allowance), 4-second segments, 5 variants, 20 KB thumbnail, 4 KB
metadata/video, encode cost 2 CPU-hours/source (unverified).

* Arrival = 1e6/86400 = 11.57 uploads/s; source = 500 TB/day, 182.5 PB/year before replicas.
* Encoded bytes/source = 600 s ×10.7e6/8 = 802.5 MB; encoded multiplier = 1.605× source; total source+encoded = 1.3025
  PB/day = 475.4125 PB/year.
* Thumbnails = 20 GB/day, 7.3 TB/year. Five variants ×150 segments +5 playlists +master+thumbnail =757 output
  objects/upload; 757 million objects/day plus sources. Larger segment duration trades object cost against ABR
  responsiveness.
* CPU = 2 million CPU-hours/day; steady CPU equivalents =83,333. At 2 cores/job and 1 h wall time/job, concurrency =
  41,667 jobs, before peak/headroom. Measure encoding speed by profile; 4K/AV1 costs differ radically. Queue residence =
  backlog / completed-jobs-per-second.
* Video metadata =4 GB/day; users/history/indexes/replicas are additional. At100M videos ~400 GB raw metadata, not an
  all-in database estimate.
* Playback starts 1M/min =16,667 sessions/s. Events at start +15-second progress for10M viewers: about666,667 progress/s
  plus starts/completion. At300 bytes/event ~200 MB/s payload; broker fanout/replication multiplies. Baseline database
  events cannot sustain this; use edge analytics ingestion, partitioned log and batch progress writes at that scale.
* Kafka throughput for implemented deployment =0; Kafka is an extension, not a required idle dependency.

|    Viewers |   2 Mbps |   5 Mbps |  10 Mbps |
|-----------:|---------:|---------:|---------:|
|    100,000 | 200 Gbps | 500 Gbps |   1 Tbps |
|  1,000,000 |   2 Tbps |   5 Tbps |  10 Tbps |
| 10,000,000 |  20 Tbps |  50 Tbps | 100 Tbps |

Egress = viewers × delivered bitrate; add a stated 5% illustrative protocol/retry margin: 50 Tbps becomes52.5 Tbps. One
day at50 Tbps =540 PB. Origin = edge egress ×(1-byte-hit-ratio); at99% hit ratio,50 Tbps edge gives500 Gbps origin
before margin. A request hit ratio is not a byte hit ratio. Four-second media yields2.5M segment requests/s for10M
viewers (more with separate audio); sessions are neither API RPS nor segments/s.

Cross-region replication = new replicated bytes × destination count × provider price/GB; e.g.1.3025 PB/day to one
additional region, excluding retries and metadata. Cost = storage GB-month by tier + requests + encoded CPU/GPU-hours +
CDN GB egress + origin GB + replication GB; substitute contracted regional rates rather than inventing a bill.

1M starts/min and100k metadata API RPS require partitioning, read replicas, candidate caching and edge auth, not just
more Spring pods. Use API metadata workloads and CDN segment workloads separately. All requested global-scale targets
remain unverified.
