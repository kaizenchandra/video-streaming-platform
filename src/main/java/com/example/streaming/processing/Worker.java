package com.example.streaming.processing;

import com.example.streaming.shared.Db;
import com.example.streaming.upload.Storage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "media.worker", havingValue = "true")
public class Worker {
    private final Db db;
    private final Storage storage;
    private final Encoder encoder;
    private final TransactionTemplate tx;
    private final Path temp;
    private final MediaScanner scanner;
    private final boolean autoApprove;
    private final long minimumFree;
    private final ScratchSpace scratch;

    @org.springframework.beans.factory.annotation.Autowired
    public Worker(
            Db d,
            Storage s,
            Encoder e,
            TransactionTemplate t,
            @Value("${media.temp}") String path,
            MediaScanner scanner,
            @Value("${media.moderation.auto-approve:false}") boolean autoApprove,
            @Value("${media.scratch-min-free-bytes:53687091200}") long minimumFree,
            ScratchSpace scratch) {
        db = d;
        storage = s;
        encoder = e;
        tx = t;
        temp = Path.of(path);
        this.scanner = scanner;
        this.autoApprove = autoApprove;
        this.minimumFree = minimumFree;
        this.scratch = scratch;
    }

    public Worker(
            Db d,
            Storage s,
            Encoder e,
            TransactionTemplate t,
            String path,
            MediaScanner scanner,
            boolean autoApprove,
            long minimumFree) {
        this(
                d,
                s,
                e,
                t,
                path,
                scanner,
                autoApprove,
                minimumFree,
                new ScratchSpace(d, t, path, 86400, true, false));
    }

    private static String errorCode(Exception error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.io.IOException
                    && t.getMessage() != null
                    && t.getMessage().toLowerCase(java.util.Locale.ROOT).contains("no space left"))
                return "SCRATCH_FULL";
        }
        return error.getClass().getSimpleName();
    }

    private static long fileSize(Path p) {
        try {
            return Files.size(p);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private String scanEvidence() {
        try {
            return scanner.lastEvidence() == null
                    ? "{}"
                    : new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(scanner.lastEvidence());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Scan audit serialization failed", e);
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        db.sql.update(
                "with expired as (update jobs set state='FAILED',error='Lease exhausted' where"
                        + " state='RUNNING' and lease_until<now() and attempts>=3 returning attempt) update"
                        + " processing_attempts set state='FAILED',error='Lease exhausted',finished_at=now()"
                        + " where id in (select attempt from expired)");
        db.sql.update(
                "update videos v set state='FAILED' where not v.deleted and v.state='QUEUED' and"
                        + " v.current_attempt is null and exists(select 1 from jobs j where j.video_id=v.id and"
                        + " j.state='FAILED') and not exists(select 1 from jobs j where j.video_id=v.id and"
                        + " j.state in ('QUEUED','RUNNING'))");
        var job =
                tx.execute(
                        t -> {
                            var rows =
                                    db.sql.queryForList(
                                            "select * from jobs where (state='QUEUED' or (state='RUNNING' and"
                                                    + " lease_until<now())) and attempts<3 and available_at<=now() order by"
                                                    + " created_at for update skip locked limit 1");
                            if (rows.isEmpty()) return null;
                            var j = rows.getFirst();
                            db.sql.update(
                                    "update processing_attempts set state='EXPIRED',finished_at=now() where id=? and"
                                            + " state='RUNNING'",
                                    j.get("attempt"));
                            UUID attempt = UUID.randomUUID();
                            db.sql.update(
                                    "update jobs set"
                                            + " state='RUNNING',attempt=?,attempts=attempts+1,lease_until=now()+interval"
                                            + " '90 seconds' where id=?",
                                    attempt,
                                    j.get("id"));
                            db.sql.update(
                                    "insert into processing_attempts(id,job_id) values(?,?)", attempt, j.get("id"));
                            j.put("attempt", attempt);
                            return j;
                        });
        if (job == null) return;
        UUID attempt = (UUID) job.get("attempt");
        Object jobId = job.get("id");
        Path dir = temp.resolve(attempt.toString());
        Thread current = Thread.currentThread();
        var heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(
                () -> {
                    try {
                        if (db.sql.update(
                                "update jobs set lease_until=now()+interval '90 seconds' where id=? and"
                                        + " attempt=? and state='RUNNING'",
                                jobId,
                                attempt)
                                == 0) current.interrupt();
                    } catch (Exception e) {
                        current.interrupt();
                    }
                },
                15,
                15,
                TimeUnit.SECONDS);
        long started = System.nanoTime();
        ScratchSpace.Lease scratchLease = null;
        try {
            scratchLease = scratch.begin((UUID) jobId, attempt);
            if (Files.getFileStore(dir).getUsableSpace() < minimumFree)
                throw new IllegalStateException("Insufficient scratch space");
            Path source = dir.resolve("source");
            storage.download((String) job.get("source_key"), source);
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(source)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) != -1) digest.update(b, 0, n);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(job.get("sha256")))
                throw new IllegalArgumentException("Source checksum mismatch");
            String scan = scanner.scan(source);
            var coverage = scanner.lastEvidence();
            if (coverage != null
                    && (!coverage.complete() || !Objects.equals(coverage.sha256(), job.get("sha256"))))
                throw new IllegalStateException("Scan/source checksum mismatch");
            db.sql.update(
                    "update processing_attempts set scan_evidence=?::jsonb where id=?",
                    scanEvidence(),
                    attempt);
            var engine = scanner.lastEngine();
            db.sql.update(
                    "update processing_attempts set"
                            + " scan_result=?,scan_engine=?,scan_database=?,scan_database_built_at=?,scan_checked_at=now()"
                            + " where id=?",
                    scan,
                    engine == null ? null : engine.version(),
                    engine == null ? null : engine.database(),
                    engine == null ? null : java.sql.Timestamp.from(engine.builtAt()),
                    attempt);
            Path out = dir.resolve("output");
            var result = encoder.encode(source, out);
            String prefix = "videos/" + job.get("video_id") + "/" + attempt + "/";
            try (var paths = Files.walk(out)) {
                for (Path p :
                        paths
                                .filter(Files::isRegularFile)
                                .sorted(Comparator.comparing(p -> p.getFileName().toString().equals("master.m3u8")))
                                .toList()) {
                    if (Thread.currentThread().isInterrupted()
                            || db.sql.queryForObject(
                            "select count(*) from jobs where id=? and attempt=? and state='RUNNING'",
                            Integer.class,
                            jobId,
                            attempt)
                            == 0) throw new InterruptedException("Attempt cancelled");
                    String name = out.relativize(p).toString();
                    String key = prefix + name;
                    tx.executeWithoutResult(
                            t -> {
                                var fence = db.one("select * from jobs where id=? for update", jobId);
                                Db.require(
                                        attempt.equals(fence.get("attempt")) && "RUNNING".equals(fence.get("state")),
                                        "Write fenced");
                                storage.put(
                                        key,
                                        p,
                                        name.endsWith("m3u8")
                                                ? "application/vnd.apple.mpegurl"
                                                : name.endsWith("ts") ? "video/mp2t" : "image/jpeg");
                                if (storage.head(key).contentLength() != fileSize(p))
                                    throw new IllegalStateException("Output verification failed");
                            });
                }
            }
            tx.executeWithoutResult(
                    t -> {
                        db.one("select id from videos where id=? for update", job.get("video_id"));
                        var locked = db.one("select * from jobs where id=? for update", jobId);
                        Db.require(
                                attempt.equals(locked.get("attempt")) && "RUNNING".equals(locked.get("state")),
                                "Lease fencing rejected");
                        int n =
                                db.sql.update(
                                        "update videos set"
                                                + " current_attempt=?,state='READY',duration=?,moderation_status=?,published=false,version=version+1"
                                                + " where id=? and not deleted",
                                        attempt,
                                        result.duration(),
                                        autoApprove && !"WINDOWED_MP4_V1".equals(scan) ? "APPROVED" : "PENDING",
                                        job.get("video_id"));
                        Db.require(n == 1, "Video deleted");
                        for (int h : result.heights())
                            db.sql.update(
                                    "insert into renditions(attempt,height,bitrate,manifest) values(?,?,?,?)",
                                    attempt,
                                    h,
                                    Encoder.bitrate(h) * 1000,
                                    prefix + h + "p/index.m3u8");
                        db.sql.update("update jobs set state='SUCCEEDED',lease_until=null where id=?", jobId);
                        db.sql.update(
                                "update processing_attempts set state='SUCCEEDED',finished_at=now() where id=?",
                                attempt);
                        db.event((UUID) job.get("video_id"), "VideoProcessingCompleted", "{}");
                    });
        } catch (Exception e) {
            Thread.interrupted();
            tx.executeWithoutResult(
                    t -> {
                        db.one("select id from videos where id=? for update", job.get("video_id"));
                        db.sql.update(
                                "update jobs set state=case when attempts>=3 then 'FAILED' else 'QUEUED'"
                                        + " end,error=?,available_at=now()+(power(2,attempts)*5 * interval '1"
                                        + " second'),lease_until=null where id=? and attempt=? and state='RUNNING'",
                                errorCode(e),
                                jobId,
                                attempt);
                        db.sql.update(
                                "update processing_attempts set scan_evidence=?::jsonb where id=?",
                                scanEvidence(),
                                attempt);
                        var engine = scanner.lastEngine();
                        if (engine != null)
                            db.sql.update(
                                    "update processing_attempts set"
                                            + " scan_engine=?,scan_database=?,scan_database_built_at=?,scan_checked_at=now()"
                                            + " where id=?",
                                    engine.version(),
                                    engine.database(),
                                    java.sql.Timestamp.from(engine.builtAt()),
                                    attempt);
                        if (e instanceof MediaScanner.Rejected
                                && db.sql.queryForObject(
                                "select count(*) from jobs where id=? and attempt=? and state in"
                                        + " ('QUEUED','FAILED')",
                                Integer.class,
                                jobId,
                                attempt)
                                == 1) {
                            db.sql.update(
                                    "update jobs set state='BLOCKED',lease_until=null where id=? and attempt=? and"
                                            + " state in ('QUEUED','FAILED')",
                                    jobId,
                                    attempt);
                            db.sql.update(
                                    "update processing_attempts set scan_result=? where id=?",
                                    ((MediaScanner.Rejected) e).result(),
                                    attempt);
                            db.sql.update(
                                    "update videos set moderation_status='REJECTED',published=false,state=case when"
                                            + " current_attempt is null then 'BLOCKED' else state end,version=version+1"
                                            + " where id=?",
                                    job.get("video_id"));
                        }
                        db.sql.update(
                                "update processing_attempts set state='FAILED',finished_at=now(),error=? where"
                                        + " id=?",
                                errorCode(e),
                                attempt);
                        db.sql.update(
                                "update videos set state='FAILED' where id=? and current_attempt is null and"
                                        + " exists(select 1 from jobs where id=? and state='FAILED')",
                                job.get("video_id"),
                                jobId);
                    });
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Processing attempt failed: {}", e.getClass().getSimpleName());
        } finally {
            heartbeat.shutdownNow();
            var survivors = ProcessRunner.takeSurvivors();
            if (survivors.isEmpty()) scratch.finish(scratchLease);
            else scratch.quarantine(scratchLease, survivors);
            scanner.clear();
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .info("Processing attempt elapsed seconds={}", (System.nanoTime() - started) / 1e9);
        }
    }
}
