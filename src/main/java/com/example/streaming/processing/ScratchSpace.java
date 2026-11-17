package com.example.streaming.processing;

import com.example.streaming.shared.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-filesystem-volume ownership plus an OS lock held by each live processing attempt.
 */
@Component
@ConditionalOnProperty(name = "media.worker", havingValue = "true")
public class ScratchSpace {
    // On POSIX, closing any channel to an inode can release this JVM's other fcntl locks.
    // Avoid opening a second channel at all while one of our local leases owns that path.
    private static final Set<Path> heldLocks = new HashSet<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID, Quarantined> quarantined =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Db db;
    private final TransactionTemplate tx;
    private final Path root, locks;
    private final UUID volume;
    private final long retention;
    private final boolean enabled, adopt;

    public ScratchSpace(
            Db db,
            TransactionTemplate tx,
            @Value("${media.temp}") String path,
            @Value("${media.scratch.retention-seconds:86400}") long retention,
            @Value("${media.scratch.enabled:true}") boolean enabled,
            @Value("${media.scratch.adopt-legacy:false}") boolean adopt) {
        if (retention < 60)
            throw new IllegalArgumentException("Scratch retention must be >=60 seconds");
        this.db = db;
        this.tx = tx;
        this.retention = retention;
        this.enabled = enabled;
        this.adopt = adopt;
        try {
            Files.createDirectories(Path.of(path));
            root = Path.of(path).toRealPath();
            if (root.getParent() == null)
                throw new IllegalArgumentException("Scratch cannot be filesystem root");
            locks = root.resolve(".locks");
            Files.createDirectories(locks);
            if (Files.isSymbolicLink(locks))
                throw new IOException("Scratch lock directory must not be a symlink");
            volume = volumeId(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static synchronized UUID volumeId(Path root) throws IOException {
        try (var channel =
                     FileChannel.open(
                             root.resolve(".volume.guard"),
                             StandardOpenOption.CREATE,
                             StandardOpenOption.WRITE,
                             LinkOption.NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            Path marker = root.resolve(".volume-id");
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS))
                Files.writeString(marker, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW);
            if (Files.isSymbolicLink(marker)) throw new IOException("Unsafe volume marker");
            return UUID.fromString(Files.readString(marker).trim());
        }
    }

    public void quarantine(Lease lease, Set<ProcessHandle> processes) {
        if (lease != null) quarantined.put(lease.attempt, new Quarantined(lease, processes));
    }

    private void reap() {
        quarantined.forEach(
                (id, q) -> {
                    if (q.processes().stream().noneMatch(ProcessHandle::isAlive) && quarantined.remove(id, q))
                        finish(q.lease());
                });
    }

    public UUID volume() {
        return volume;
    }

    public Path path(UUID attempt) {
        return root.resolve(attempt.toString());
    }

    private Lease tryLease(UUID attempt) throws IOException {
        synchronized (ScratchSpace.class) {
            Path lockPath = locks.resolve(attempt + ".lock");
            if (heldLocks.contains(lockPath)) return null;
            FileChannel channel =
                    FileChannel.open(
                            lockPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
            try {
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    lock = null;
                }
                if (lock == null) {
                    channel.close();
                    return null;
                }
                heldLocks.add(lockPath);
                return new Lease(attempt, channel, lock);
            } catch (Throwable t) {
                channel.close();
                throw t;
            }
        }
    }

    public Lease begin(UUID job, UUID attempt) {
        var allocated = new AtomicReference<Lease>();
        try {
            return tx.execute(
                    t -> {
                        var j = db.one("select * from jobs where id=? for update", job);
                        Db.require(
                                attempt.equals(j.get("attempt")) && "RUNNING".equals(j.get("state")),
                                "Scratch creation fenced");
                        var a =
                                db.one("select * from processing_attempts where id=? and job_id=?", attempt, job);
                        Db.require(a.get("scratch_purged_at") == null, "Scratch already purged");
                        Db.require(
                                a.get("scratch_volume") == null || volume.equals(a.get("scratch_volume")),
                                "Attempt belongs to another scratch volume");
                        try {
                            Lease lease = tryLease(attempt);
                            Db.require(lease != null, "Attempt directory already in use");
                            allocated.set(lease);
                            Path path = lease.path();
                            Db.require(!Files.isSymbolicLink(path), "Unsafe attempt directory");
                            Files.createDirectories(path);
                            db.sql.update(
                                    "update processing_attempts set scratch_volume=? where id=?", volume, attempt);
                            return lease;
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (allocated.get() != null) allocated.get().close();
            throw e;
        }
    }

    /**
     * Called only after media processes and file handles have terminated. Failures retain files for a
     * retry.
     */
    public void finish(Lease lease) {
        if (lease == null) return;
        try {
            tx.executeWithoutResult(
                    t -> {
                        var a =
                                db.one(
                                        "select j.id as job_id,j.video_id from jobs j join processing_attempts a on"
                                                + " a.job_id=j.id where a.id=?",
                                        lease.attempt);
                        var v = db.one("select * from videos where id=? for update", a.get("video_id"));
                        var j = db.one("select * from jobs where id=? for update", a.get("job_id"));
                        if (Boolean.TRUE.equals(v.get("legal_hold"))
                                || ("RUNNING".equals(j.get("state")) && lease.attempt.equals(j.get("attempt"))))
                            return;
                        purge(lease);
                    });
        } catch (RuntimeException e) {
            recordError(lease.attempt, e);
        } finally {
            lease.close();
        }
    }

    private void purge(Lease lease) {
        try {
            Path directory = lease.path();
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
                Files.walkFileTree(
                        directory,
                        new SimpleFileVisitor<Path>() {
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                    throws IOException {
                                Files.delete(file);
                                return FileVisitResult.CONTINUE;
                            }

                            public FileVisitResult postVisitDirectory(Path dir, IOException error)
                                    throws IOException {
                                if (error != null) throw error;
                                Files.delete(dir);
                                return FileVisitResult.CONTINUE;
                            }
                        });
            db.sql.update(
                    "update processing_attempts set scratch_purged_at=now(),scratch_error=null where id=? and"
                            + " scratch_volume=?",
                    lease.attempt,
                    volume);
            Files.deleteIfExists(locks.resolve(lease.attempt + ".lock"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void recordError(UUID attempt, Exception e) {
        try {
            db.sql.update(
                    "update processing_attempts set scratch_error=? where id=?",
                    e.getClass().getSimpleName(),
                    attempt);
        } catch (RuntimeException ignored) {
        }
        org.slf4j.LoggerFactory.getLogger(getClass())
                .warn("Scratch cleanup deferred: {}", e.getClass().getSimpleName());
    }

    @Scheduled(fixedDelayString = "${media.scratch.interval-ms:60000}")
    public void scheduled() {
        if (enabled) runOnce();
    }

    public int runOnce() {
        reap();
        if (adopt) adoptLegacy();
        int[] count = {0};
        var candidates =
                db.sql.queryForList(
                        "select a.id,j.id as job_id,j.video_id from processing_attempts a join jobs j on"
                                + " j.id=a.job_id join videos v on v.id=j.video_id where a.scratch_volume=? and"
                                + " a.scratch_purged_at is null and not v.legal_hold and not(j.state='RUNNING' and"
                                + " j.attempt=a.id) and (coalesce(a.finished_at,a.started_at)<now()-(?*interval '1"
                                + " second') or (v.deleted and exists(select 1 from erasure_jobs e where"
                                + " e.video_id=v.id and e.not_before<=now()))) order by a.started_at limit 100",
                        volume,
                        retention);
        for (var c : candidates) {
            UUID attempt = (UUID) c.get("id");
            try {
                tx.executeWithoutResult(
                        t -> {
                            var videos =
                                    db.sql.queryForList(
                                            "select * from videos where id=? for update skip locked", c.get("video_id"));
                            if (videos.isEmpty() || Boolean.TRUE.equals(videos.getFirst().get("legal_hold")))
                                return;
                            var jobs =
                                    db.sql.queryForList(
                                            "select * from jobs where id=? for update skip locked", c.get("job_id"));
                            if (jobs.isEmpty()) return;
                            var j = jobs.getFirst();
                            if ("RUNNING".equals(j.get("state")) && attempt.equals(j.get("attempt"))) return;
                            var a = db.one("select * from processing_attempts where id=?", attempt);
                            if (a.get("scratch_purged_at") != null || !volume.equals(a.get("scratch_volume")))
                                return;
                            try (var lease = tryLease(attempt)) {
                                if (lease == null) return;
                                purge(lease);
                                count[0]++;
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            } catch (RuntimeException e) {
                recordError(attempt, e);
            }
        }
        return count[0];
    }

    /**
     * Opt-in only after draining ALL old workers: pre-V7 binaries did not hold OS locks. Unknown
     * paths stay untouched.
     */
    public void adoptLegacy() {
        try (var paths = Files.list(root)) {
            for (Path p : paths.limit(1000).toList()) {
                UUID attempt;
                try {
                    attempt = UUID.fromString(p.getFileName().toString());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue;
                tx.executeWithoutResult(
                        t -> {
                            var rows =
                                    db.sql.queryForList(
                                            "select j.id as job_id,j.video_id from processing_attempts a join jobs j on"
                                                    + " j.id=a.job_id where a.id=? and a.scratch_volume is null",
                                            attempt);
                            if (rows.isEmpty()) return;
                            var row = rows.getFirst();
                            db.one("select id from videos where id=? for update", row.get("video_id"));
                            var j = db.one("select * from jobs where id=? for update", row.get("job_id"));
                            if ("RUNNING".equals(j.get("state")) && attempt.equals(j.get("attempt"))) return;
                            db.sql.update(
                                    "update processing_attempts set scratch_volume=? where id=? and scratch_volume is"
                                            + " null",
                                    volume,
                                    attempt);
                        });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Quarantined(Lease lease, Set<ProcessHandle> processes) {
    }

    public final class Lease implements AutoCloseable {
        private final UUID attempt;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private Lease(UUID attempt, FileChannel channel, FileLock lock) {
            this.attempt = attempt;
            this.channel = channel;
            this.lock = lock;
        }

        public Path path() {
            return ScratchSpace.this.path(attempt);
        }

        public void close() {
            synchronized (ScratchSpace.class) {
                if (closed) return;
                closed = true;
                try {
                    lock.release();
                } catch (IOException ignored) {
                }
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
                heldLocks.remove(locks.resolve(attempt + ".lock"));
            }
        }
    }
}
