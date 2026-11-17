package com.example.streaming.upload;

import com.example.streaming.identity.Identity;
import com.example.streaming.shared.Db;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/videos/uploads")
public class Uploads {
    private final Db db;
    private final Identity identity;
    private final Storage storage;
    private final TransactionTemplate tx;
    private final com.example.streaming.processing.MediaScanner scanner;

    public Uploads(Db d, Identity i, Storage s, TransactionTemplate t) {
        this(d, i, s, t, new com.example.streaming.processing.MediaScanner("disabled", "", 0));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public Uploads(
            Db d,
            Identity i,
            Storage s,
            TransactionTemplate t,
            com.example.streaming.processing.MediaScanner scanner) {
        db = d;
        identity = i;
        storage = s;
        tx = t;
        this.scanner = scanner;
    }

    @PostMapping
    public Map<String, Object> start(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 100) String key,
            @Valid @RequestBody Start request) {
        if (request.bytes() > scanner.maxBytes())
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "Source exceeds configured scanner coverage");
        String user = identity.user(j);
        return tx.execute(
                t -> {
                    db.one("select id from accounts where id=? for update", user);
                    var old =
                            db.sql.queryForList(
                                    "select * from uploads where owner_id=? and idempotency_key=?", user, key);
                    if (!old.isEmpty()) {
                        var u = old.getFirst();
                        Db.require(
                                request.videoId().equals(u.get("video_id"))
                                        && request.bytes() == ((Number) u.get("expected_bytes")).longValue()
                                        && request.sha256().equals(u.get("sha256")),
                                "Idempotency key reused");
                        return u;
                    }
                    var video = db.own(request.videoId(), user);
                    db.one("select id from videos where id=? and not deleted for update", request.videoId());
                    Db.require(
                            java.util.Set.of("DRAFT", "FAILED").contains(video.get("state")),
                            "Video already has media; use reprocess");
                    Db.require(
                            db.sql.queryForObject(
                                    "select count(*) from uploads where owner_id=? and created_at>now()-interval"
                                            + " '1 hour'",
                                    Integer.class,
                                    user)
                                    < 20,
                            "Upload rate exceeded");
                    Db.require(
                            db.sql.update(
                                    "update accounts set reserved=reserved+? where id=? and"
                                            + " used+reserved+?<=quota",
                                    request.bytes(),
                                    user,
                                    request.bytes())
                                    == 1,
                            "Storage quota exceeded");
                    UUID id = UUID.randomUUID();
                    String object = "sources/" + id;
                    db.sql.update(
                            "insert into"
                                    + " uploads(id,video_id,owner_id,idempotency_key,object_key,expected_bytes,sha256)"
                                    + " values(?,?,?,?,?,?,?)",
                            id,
                            request.videoId(),
                            user,
                            key,
                            object,
                            request.bytes(),
                            request.sha256());
                    String multipart = storage.initiate(object);
                    db.sql.update(
                            "update uploads set multipart_id=?,state='UPLOADING' where id=?", multipart, id);
                    db.sql.update(
                            "update videos set state='UPLOADING' where id=? and state in ('DRAFT','FAILED')",
                            request.videoId());
                    return db.one("select * from uploads where id=?", id);
                });
    }

    private Map<String, Object> own(UUID id, String user) {
        var u = db.one("select * from uploads where id=?", id);
        Db.require(user.equals(u.get("owner_id")), "Upload owner required");
        return u;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return own(id, identity.user(j));
    }

    @PostMapping("/{id}/parts")
    public List<Map<String, Object>> parts(
            @AuthenticationPrincipal Jwt j, @PathVariable UUID id, @Valid @RequestBody Parts p) {
        var u = own(id, identity.user(j));
        Db.require("UPLOADING".equals(u.get("state")), "Upload not active");
        Db.require(
                ((java.sql.Timestamp) u.get("expires_at")).toInstant().isAfter(java.time.Instant.now()),
                "Upload expired");
        Db.require(p.first() + p.count() - 1 <= 10000, "Too many parts");
        long chunk = 8L * 1024 * 1024;
        long expected = ((Number) u.get("expected_bytes")).longValue();
        long count = (expected + chunk - 1) / chunk;
        Db.require(p.first() + p.count() - 1 <= count, "Part number exceeds reserved source size");
        var urls = new ArrayList<Map<String, Object>>();
        for (int n = p.first(); n < p.first() + p.count(); n++) {
            long bytes = Math.min(chunk, expected - (n - 1) * chunk);
            urls.add(
                    Map.of(
                            "number",
                            n,
                            "contentLength",
                            bytes,
                            "url",
                            storage.part(
                                    (String) u.get("object_key"), (String) u.get("multipart_id"), n, bytes)));
        }
        return urls;
    }

    @PostMapping("/{id}/complete")
    public Map<String, Object> complete(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        own(id, identity.user(j));
        return finish(id);
    }

    public Map<String, Object> finish(UUID id) {
        return tx.execute(
                t -> {
                    var u = db.one("select * from uploads where id=? for update", id);
                    if ("COMPLETED".equals(u.get("state"))) return u;
                    Db.require("UPLOADING".equals(u.get("state")), "Upload not active");
                    db.one("select id from videos where id=? and not deleted for update", u.get("video_id"));
                    String key = (String) u.get("object_key");
                    storage.complete(
                            key, (String) u.get("multipart_id"), ((Number) u.get("expected_bytes")).longValue());
                    Db.require(
                            storage.head(key).contentLength() == ((Number) u.get("expected_bytes")).longValue(),
                            "Object size mismatch");
                    db.sql.update("update uploads set state='COMPLETED' where id=?", id);
                    db.sql.update(
                            "update accounts set reserved=reserved-?,used=used+? where id=?",
                            u.get("expected_bytes"),
                            u.get("expected_bytes"),
                            u.get("owner_id"));
                    db.sql.update("update videos set state='QUEUED' where id=?", u.get("video_id"));
                    db.sql.update(
                            "insert into jobs(id,video_id,source_key,sha256) values(?,?,?,?)",
                            UUID.randomUUID(),
                            u.get("video_id"),
                            key,
                            u.get("sha256"));
                    db.event((UUID) u.get("video_id"), "VideoUploadCompleted", "{}");
                    return db.one("select * from uploads where id=?", id);
                });
    }

    @PostMapping("/{id}/abort")
    public void abort(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        own(id, identity.user(j));
        cancel(id);
    }

    private void cancel(UUID id) {
        tx.executeWithoutResult(
                t -> {
                    var u = db.one("select * from uploads where id=? for update", id);
                    if ("ABORTED".equals(u.get("state"))) return;
                    Db.require("UPLOADING".equals(u.get("state")), "Cannot abort completed upload");
                    Db.require(
                            !sourceExists((String) u.get("object_key")),
                            "Source already committed; retry completion");
                    storage.abort((String) u.get("object_key"), (String) u.get("multipart_id"));
                    Db.require(
                            !sourceExists((String) u.get("object_key")),
                            "Source committed concurrently; retry completion");
                    db.sql.update("update uploads set state='ABORTED' where id=?", id);
                    db.sql.update(
                            "update videos set state='DRAFT' where id=? and state='UPLOADING' and current_attempt"
                                    + " is null and not deleted",
                            u.get("video_id"));
                    db.sql.update(
                            "update accounts set reserved=reserved-? where id=?",
                            u.get("expected_bytes"),
                            u.get("owner_id"));
                });
    }

    private boolean sourceExists(String key) {
        try {
            storage.head(key);
            return true;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() != 404) throw e;
            return false;
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void reconcile() {
        for (var u :
                db.sql.queryForList(
                        "select id,object_key,expected_bytes from uploads where state='UPLOADING' and"
                                + " expires_at<now() limit 50")) {
            UUID id = (UUID) u.get("id");
            try {
                boolean exists;
                try {
                    exists =
                            storage.head((String) u.get("object_key")).contentLength()
                                    == ((Number) u.get("expected_bytes")).longValue();
                } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
                    if (e.statusCode() != 404) throw e;
                    exists = false;
                }
                if (exists) finish(id);
                else cancel(id);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Upload reconciliation deferred: {}", e.getClass().getSimpleName());
            }
        }
    }

    public record Start(
            @NotNull UUID videoId,
            @Min(1) @Max(21474836480L) long bytes,
            @Pattern(regexp = "[a-f0-9]{64}") @NotNull String sha256) {
    }

    public record Parts(@Min(1) @Max(10000) int first, @Min(1) @Max(100) int count) {
    }
}
