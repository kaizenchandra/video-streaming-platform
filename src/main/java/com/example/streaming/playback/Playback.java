package com.example.streaming.playback;

import com.example.streaming.identity.Identity;
import com.example.streaming.shared.Db;
import com.example.streaming.upload.Storage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
public class Playback {
    private final Db db;
    private final Identity identity;
    private final Storage storage;
    private final CdnCredentials cdn;
    private final com.example.streaming.moderation.Moderation moderation;

    public Playback(
            Db d,
            Identity i,
            Storage s,
            CdnCredentials cdn,
            com.example.streaming.moderation.Moderation moderation) {
        db = d;
        identity = i;
        storage = s;
        this.cdn = cdn;
        this.moderation = moderation;
    }

    @PostMapping("/api/v1/videos/{id}/playback-sessions")
    @Transactional
    public ResponseEntity<Map<String, Object>> start(
            @AuthenticationPrincipal Jwt j, @PathVariable UUID id)
            throws java.security.GeneralSecurityException {
        return session(j, id, false);
    }

    @PostMapping("/api/v1/videos/{id}/review-playback-sessions")
    @Transactional
    public ResponseEntity<Map<String, Object>> review(
            @AuthenticationPrincipal Jwt j, @PathVariable UUID id)
            throws java.security.GeneralSecurityException {
        moderation.reviewer(j);
        return session(j, id, true);
    }

    private ResponseEntity<Map<String, Object>> session(Jwt j, UUID id, boolean review)
            throws java.security.GeneralSecurityException {
        String u = identity.user(j);
        var v = db.one("select * from videos where id=? and not deleted for update", id);
        if (!review) db.readable(v, u);
        Db.require(
                v.get("current_attempt") != null
                        && (review || !"REJECTED".equals(v.get("moderation_status"))),
                "Media unavailable or blocked");
        Db.require(
                Boolean.TRUE.equals(
                        db.one("select active from accounts where id=?", v.get("owner_id")).get("active")),
                "Creator deactivated");
        Db.require(
                moderation.scanAccepted((UUID) v.get("current_attempt")),
                "Source scan policy not satisfied");
        UUID sid = UUID.randomUUID();
        db.sql.update(
                "insert into playback_sessions(id,video_id,user_id,attempt,purpose,expires_at)"
                        + " values(?,?,?,?,?,now()+(? * interval '1 second'))",
                sid,
                id,
                u,
                v.get("current_attempt"),
                review ? "REVIEW" : "WATCH",
                review ? 900 : 14400);
        db.event(
                id,
                review ? "ReviewPlaybackStarted" : "PlaybackStarted",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode()
                        .put("session", sid.toString())
                        .put("actor", u)
                        .toString());
        var progress =
                review
                        ? List.<Map<String, Object>>of()
                        : db.sql.queryForList(
                        "select position from watch_progress where user_id=? and video_id=?", u, id);
        String prefix = "/media/videos/" + id + "/" + v.get("current_attempt") + "/";
        if (cdn.enabled()) {
            var response = ResponseEntity.ok();
            for (String cookie : cdn.cookies(id, (UUID) v.get("current_attempt"), Instant.now()))
                response.header(HttpHeaders.SET_COOKIE, cookie);
            return response.body(
                    Map.of(
                            "sessionId",
                            sid,
                            "manifest",
                            cdn.path(id, (UUID) v.get("current_attempt")) + "master.m3u8",
                            "resume",
                            progress.isEmpty() ? 0 : progress.getFirst().get("position"),
                            "credentialTtl",
                            cdn.ttl()));
        }
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        ResponseCookie.from("playback", sid.toString())
                                .httpOnly(true)
                                .sameSite("Strict")
                                .path(prefix)
                                .maxAge(Duration.ofSeconds(review ? 900 : 14400))
                                .build()
                                .toString())
                .body(
                        Map.of(
                                "sessionId",
                                sid,
                                "manifest",
                                prefix + "master.m3u8",
                                "resume",
                                progress.isEmpty() ? 0 : progress.getFirst().get("position")));
    }

    @GetMapping("/api/v1/videos/{id}/playback")
    public Map<String, Object> info(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        var v = db.video(id);
        db.readable(v, identity.user(j));
        return Map.of(
                "ready",
                v.get("current_attempt") != null,
                "duration",
                Objects.requireNonNullElse(v.get("duration"), 0));
    }

    @GetMapping("/internal/media-auth")
    public ResponseEntity<Void> authorize(
            @CookieValue(value = "playback", required = false) String cookie,
            @RequestHeader("X-Original-URI") String path) {
        try {
            UUID session = UUID.fromString(cookie);
            var row =
                    db.one(
                            "select s.video_id,s.attempt from playback_sessions s join videos v on"
                                    + " v.id=s.video_id join accounts a on a.id=s.user_id join accounts creator on"
                                    + " creator.id=v.owner_id where s.id=? and s.expires_at>now() and not v.deleted"
                                    + " and a.active and creator.active and (s.purpose='REVIEW' or"
                                    + " (v.moderation_status<>'REJECTED' and (v.owner_id=s.user_id or (v.published"
                                    + " and v.visibility<>'PRIVATE'))))",
                            session);
            if (!moderation.scanAccepted((UUID) row.get("attempt")))
                return ResponseEntity.status(403).build();
            String prefix = "/media/videos/" + row.get("video_id") + "/" + row.get("attempt") + "/";
            if (!path.startsWith(prefix)) return ResponseEntity.status(403).build();
            String relative = path.substring(prefix.length());
            if (!relative.matches(
                    "(?:master\\.m3u8|cover\\.jpg|[0-9]+p/(?:index\\.m3u8|segment_[0-9]+\\.ts))"))
                return ResponseEntity.status(403).build();
            String url = storage.getUrl(path.substring("/media/".length()));
            return ResponseEntity.ok().header("X-Storage-Url", url).build();
        } catch (Exception e) {
            return ResponseEntity.status(403).build();
        }
    }

    @PutMapping("/api/v1/videos/{id}/watch-progress")
    @Transactional
    public void progress(
            @AuthenticationPrincipal Jwt j, @PathVariable UUID id, @Valid @RequestBody Progress p) {
        String u = identity.user(j);
        var s =
                db.one(
                        "select s.*,v.duration from playback_sessions s join videos v on v.id=s.video_id where"
                                + " s.id=? and s.user_id=? and s.video_id=? and s.expires_at>now() and not"
                                + " v.deleted for update of s",
                        p.sessionId(),
                        u,
                        id);
        if ("REVIEW".equals(s.get("purpose"))) return;
        long seq = ((Number) s.get("last_sequence")).longValue();
        if (p.sequence() <= seq) return;
        double duration = ((Number) s.get("duration")).doubleValue();
        Db.require(Double.isFinite(p.position()) && p.position() <= duration + 1, "Invalid position");
        db.sql.update(
                "update playback_sessions set last_sequence=? where id=?", p.sequence(), p.sessionId());
        boolean complete = p.position() >= duration * .95;
        db.sql.update(
                "insert into watch_progress(user_id,video_id,session_id,position,completed)"
                        + " values(?,?,?,?,?) on conflict(user_id,video_id) do update set"
                        + " session_id=excluded.session_id,position=excluded.position,completed=excluded.completed,updated_at=now()"
                        + " where watch_progress.session_id=excluded.session_id or (select created_at from"
                        + " playback_sessions where id=watch_progress.session_id)<(select created_at from"
                        + " playback_sessions where id=excluded.session_id)",
                u,
                id,
                p.sessionId(),
                p.position(),
                complete);
        double elapsed =
                Duration.between(((java.sql.Timestamp) s.get("created_at")).toInstant(), Instant.now())
                        .toSeconds();
        if (p.position() >= Math.min(30, duration * .8)
                && elapsed >= Math.min(30, duration * .8)
                && !Boolean.TRUE.equals(s.get("qualified"))) {
            db.sql.update("update playback_sessions set qualified=true where id=?", p.sessionId());
            db.event(id, "QualifiedView", "{\"session\":\"" + p.sessionId() + "\"}");
        }
        db.event(
                id,
                complete ? "PlaybackCompleted" : "PlaybackProgressed",
                "{\"session\":\"" + p.sessionId() + "\",\"sequence\":" + p.sequence() + "}");
    }

    @GetMapping("/api/v1/users/me/continue-watching")
    public List<Map<String, Object>> history(@AuthenticationPrincipal Jwt j) {
        return db.sql.queryForList(
                "select w.*,v.title from watch_progress w join videos v on v.id=w.video_id where"
                        + " w.user_id=? and not w.completed and not v.deleted and (v.owner_id=? or (v.published"
                        + " and v.visibility<>'PRIVATE')) order by w.updated_at desc limit 50",
                identity.user(j),
                j.getSubject());
    }

    public record Progress(
            @NotNull UUID sessionId,
            @Min(0) long sequence,
            @DecimalMin("0") double position,
            boolean completed) {
    }
}
