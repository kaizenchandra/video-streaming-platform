package com.example.streaming.retention;

import com.example.streaming.shared.Db;
import com.example.streaming.upload.Storage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Component
public class GarbageCollector {
    private final Db db;
    private final Storage storage;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public GarbageCollector(
            Db d,
            Storage s,
            TransactionTemplate t,
            @Value("${media.erasure.enabled:false}") boolean enabled) {
        db = d;
        storage = s;
        tx = t;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 60000)
    public void scheduled() {
        if (enabled) runOne();
    }

    public void runOne() {
        tx.executeWithoutResult(
                t -> {
                    var rows =
                            db.sql.queryForList(
                                    "select j.id as job_id,a.id as attempt,j.video_id from processing_attempts a join"
                                            + " jobs j on j.id=a.job_id where a.collected_at is null and"
                                            + " a.started_at<now()-interval '7 days' and not(j.state='RUNNING' and"
                                            + " j.attempt=a.id) and not exists(select 1 from videos v where"
                                            + " v.id=j.video_id and (v.current_attempt=a.id or v.deleted or"
                                            + " v.legal_hold)) order by a.started_at limit 1");
                    if (rows.isEmpty()) return;
                    var a = rows.getFirst();
                    UUID attempt = (UUID) a.get("attempt");
                    var locked =
                            db.sql.queryForList(
                                    "select * from videos where id=? for update skip locked", a.get("video_id"));
                    if (locked.isEmpty()) return;
                    var v = locked.getFirst();
                    var jobs =
                            db.sql.queryForList(
                                    "select * from jobs where id=? for update skip locked", a.get("job_id"));
                    if (jobs.isEmpty()) return;
                    var job = jobs.getFirst();
                    if ("RUNNING".equals(job.get("state")) && attempt.equals(job.get("attempt"))) return;
                    if (Boolean.TRUE.equals(v.get("legal_hold"))
                            || Boolean.TRUE.equals(v.get("deleted"))
                            || attempt.equals(v.get("current_attempt"))) return;
                    if (db.sql.queryForObject(
                            "select count(*) from playback_sessions where attempt=? and expires_at>now()",
                            Integer.class,
                            attempt)
                            > 0) return;
                    // No API restores old attempts. Video lock serializes new playback with pointer
                    // publication.
                    if (storage.erasePage("videos/" + a.get("video_id") + "/" + attempt + "/")) {
                        db.sql.update("update processing_attempts set collected_at=now() where id=?", attempt);
                        db.sql.update("delete from renditions where attempt=?", attempt);
                    }
                });
        collectAborted();
    }

    private void collectAborted() {
        tx.executeWithoutResult(
                t -> {
                    var candidates =
                            db.sql.queryForList(
                                    "select u.id,u.video_id from uploads u join videos v on v.id=u.video_id where"
                                            + " u.state='ABORTED' and u.erased_at is null and u.created_at<now()-interval"
                                            + " '7 days' and not v.deleted and not v.legal_hold order by u.created_at for"
                                            + " update of v skip locked limit 1");
                    if (candidates.isEmpty()) return;
                    var candidate = candidates.getFirst();
                    var u = db.one("select * from uploads where id=? for update", candidate.get("id"));
                    if (!"ABORTED".equals(u.get("state")) || u.get("erased_at") != null) return;
                    if (u.get("multipart_id") != null)
                        storage.abort((String) u.get("object_key"), (String) u.get("multipart_id"));
                    if (storage.erasePage((String) u.get("object_key")))
                        db.sql.update("update uploads set erased_at=now() where id=?", u.get("id"));
                });
    }
}
