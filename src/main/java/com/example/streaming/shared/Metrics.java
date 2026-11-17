package com.example.streaming.shared;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class Metrics {
    private final Db db;
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong uploads = new AtomicLong();
    private final AtomicLong scratch = new AtomicLong(), scratchErrors = new AtomicLong();
    private final AtomicLong erasures = new AtomicLong(), moderation = new AtomicLong();

    public Metrics(Db db, MeterRegistry registry) {
        this.db = db;
        registry.gauge("streaming.jobs.queued", queued);
        registry.gauge("streaming.uploads.active", uploads);
        registry.gauge("streaming.erasure.pending", erasures);
        registry.gauge("streaming.scratch.pending", scratch);
        registry.gauge("streaming.scratch.cleanup.errors", scratchErrors);
        registry.gauge("streaming.moderation.pending", moderation);
    }

    @Scheduled(fixedDelay = 15000)
    public void sample() {
        scratchErrors.set(
                db.sql.queryForObject(
                        "select count(*) from processing_attempts where scratch_purged_at is null and"
                                + " scratch_error is not null",
                        Long.class));
        scratch.set(
                db.sql.queryForObject(
                        "select count(*) from processing_attempts where scratch_volume is not null and"
                                + " scratch_purged_at is null",
                        Long.class));
        erasures.set(
                db.sql.queryForObject(
                        "select count(*) from erasure_jobs where state='PENDING'", Long.class));
        moderation.set(
                db.sql.queryForObject(
                        "select count(*) from videos where moderation_status='PENDING' and not deleted",
                        Long.class));
        queued.set(db.sql.queryForObject("select count(*) from jobs where state='QUEUED'", Long.class));
        uploads.set(
                db.sql.queryForObject("select count(*) from uploads where state='UPLOADING'", Long.class));
    }
}
