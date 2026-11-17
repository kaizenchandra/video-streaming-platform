package com.example.streaming.retention;

import com.example.streaming.shared.Db;
import com.example.streaming.upload.Storage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class Erasure {
    private final Db db;
    private final Storage storage;
    private final TransactionTemplate tx;
    private final long delay;
    private final boolean enabled;

    public Erasure(
            Db d,
            Storage s,
            TransactionTemplate t,
            @Value("${media.erasure.grace-seconds:86400}") long delay,
            @Value("${media.erasure.enabled:false}") boolean enabled) {
        if (delay < 1200)
            throw new IllegalArgumentException(
                    "Erasure grace must be >= 20 minutes for in-flight storage requests");
        db = d;
        storage = s;
        tx = t;
        this.delay = delay;
        this.enabled = enabled;
    }

    /**
     * Video locks precede job locks, matching worker publication and scratch cleanup. No new job may
     * be inserted after deletion.
     */
    public void request(UUID id, String user) {
        tx.executeWithoutResult(
                t -> {
                    var v = db.one("select * from videos where id=? for update", id);
                    if (!user.equals(v.get("owner_id")))
                        throw new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.FORBIDDEN, "Video owner required");
                    if (Boolean.TRUE.equals(v.get("deleted"))) return;
                    db.sql.queryForList("select id from jobs where video_id=? order by id for update", id);
                    db.sql.update(
                            "update videos set"
                                    + " deleted=true,published=false,deleted_at=coalesce(deleted_at,now()),version=version+1"
                                    + " where id=?",
                            id);
                    db.sql.update(
                            "update jobs set state='CANCELLED',lease_until=null where video_id=? and state in"
                                    + " ('QUEUED','RUNNING')",
                            id);
                    db.sql.update(
                            "insert into erasure_jobs(video_id,not_before) values(?,now()+(? * interval '1"
                                    + " second')) on conflict do nothing",
                            id,
                            delay);
                    db.event(id, "VideoDeleted", "{}");
                });
    }

    @Scheduled(fixedDelayString = "${media.erasure.interval-ms:60000}")
    public void scheduled() {
        if (enabled) runOne();
    }

    /**
     * One bounded page per prefix. Object-store deletion is idempotent; commit can be retried after a
     * crash.
     */
    public void runOne() {
        tx.executeWithoutResult(
                t -> {
                    var rows =
                            db.sql.queryForList(
                                    "select e.video_id from erasure_jobs e join videos v on v.id=e.video_id where"
                                            + " e.state='PENDING' and e.not_before<=now() and not v.legal_hold order by"
                                            + " e.not_before for update of e skip locked limit 1");
                    if (rows.isEmpty()) return;
                    UUID id = (UUID) rows.getFirst().get("video_id");
                    var video = db.one("select * from videos where id=? for update", id);
                    if (Boolean.TRUE.equals(video.get("legal_hold"))) return;
                    Db.require(Boolean.TRUE.equals(video.get("deleted")), "Refuse erasure of live video");
                    db.sql.update("update erasure_jobs set attempts=attempts+1 where video_id=?", id);
                    try {
                        boolean empty = storage.erasePage("videos/" + id + "/");
                        for (var u :
                                db.sql.queryForList(
                                        "select * from uploads where video_id=? and erased_at is null limit 50", id)) {
                            if (u.get("multipart_id") != null)
                                storage.abort((String) u.get("object_key"), (String) u.get("multipart_id"));
                            boolean sourceEmpty = storage.erasePage((String) u.get("object_key"));
                            empty = sourceEmpty && empty;
                            if (sourceEmpty) {
                                if ("COMPLETED".equals(u.get("state")))
                                    db.sql.update(
                                            "update accounts set used=greatest(0,used-?) where id=?",
                                            u.get("expected_bytes"),
                                            u.get("owner_id"));
                                else if (!"ABORTED".equals(u.get("state")))
                                    db.sql.update(
                                            "update accounts set reserved=greatest(0,reserved-?) where id=?",
                                            u.get("expected_bytes"),
                                            u.get("owner_id"));
                                db.sql.update(
                                        "update uploads set erased_at=now(),state=case when state='COMPLETED' then"
                                                + " state else 'ABORTED' end where id=?",
                                        u.get("id"));
                            }
                        }
                        if (empty
                                && db.sql.queryForObject(
                                "select count(*) from uploads where video_id=? and erased_at is null",
                                Integer.class,
                                id)
                                == 0) {
                            for (String table :
                                    List.of(
                                            "watch_progress",
                                            "playback_sessions",
                                            "video_likes",
                                            "comments",
                                            "watchlist"))
                                db.sql.update("delete from " + table + " where video_id=?", id);
                            db.sql.update(
                                    "update videos set title='Deleted"
                                            + " video',description='',tags='',current_attempt=null where id=?",
                                    id);
                            db.sql.update(
                                    "update erasure_jobs set state='DONE',completed_at=now(),last_error=null where"
                                            + " video_id=?",
                                    id);
                            db.event(id, "VideoObjectsErased", "{}");
                        }
                    } catch (software.amazon.awssdk.core.exception.SdkException e) {
                        db.sql.update(
                                "update erasure_jobs set last_error=? where video_id=?",
                                e.getClass().getSimpleName(),
                                id);
                    }
                });
    }
}
