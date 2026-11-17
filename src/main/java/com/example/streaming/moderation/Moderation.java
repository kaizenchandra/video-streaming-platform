package com.example.streaming.moderation;

import com.example.streaming.identity.Identity;
import com.example.streaming.shared.Db;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class Moderation {
    private final Db db;
    private final Identity identity;
    private final com.example.streaming.processing.MediaScanner scanner;

    public Moderation(Db d, Identity i, com.example.streaming.processing.MediaScanner scanner) {
        this.scanner = scanner;
        db = d;
        identity = i;
    }

    public void reviewer(Jwt j) {
        identity.user(j);
        var roles = j.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("MODERATOR"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Moderator role required");
    }

    @GetMapping("/moderation/videos")
    public List<Map<String, Object>> pending(@AuthenticationPrincipal Jwt j) {
        reviewer(j);
        return db.sql.queryForList(
                "select"
                        + " v.id,v.title,v.current_attempt,v.version,v.created_at,a.scan_result,a.scan_engine,a.scan_database,"
                        + " a.scan_evidence->>'policy' as scan_policy,a.scan_evidence->>'coveredBytes' as"
                        + " scanned_bytes, a.scan_evidence->>'sourceBytes' as source_bytes from videos v join"
                        + " processing_attempts a on a.id=v.current_attempt where not v.deleted and"
                        + " v.state='READY' and v.moderation_status='PENDING' order by v.created_at,v.id limit"
                        + " 100");
    }

    @PostMapping("/videos/{id}/moderation-decisions")
    @Transactional
    public Map<String, Object> decide(
            @AuthenticationPrincipal Jwt j,
            @PathVariable UUID id,
            @RequestHeader("If-Match") long version,
            @Valid @RequestBody Decision d) {
        reviewer(j);
        var v = db.one("select * from videos where id=? and not deleted for update", id);
        Db.require(
                version == ((Number) v.get("version")).longValue()
                        && d.attempt().equals(v.get("current_attempt")),
                "Stale moderation review");
        Db.require(
                !"APPROVED".equals(d.decision()) || scanAccepted(d.attempt()),
                "Source scan policy not satisfied");
        db.sql.update(
                "insert into"
                        + " moderation_decisions(id,video_id,attempt,reviewer,decision,reason,video_version)"
                        + " values(?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                id,
                d.attempt(),
                j.getSubject(),
                d.decision(),
                d.reason(),
                version);
        db.sql.update(
                "update videos set moderation_status=?,published=case when ?='REJECTED' then false else"
                        + " published end,version=version+1 where id=?",
                d.decision(),
                d.decision(),
                id);
        db.event(id, "ModerationDecided", "{}");
        return db.video(id);
    }

    public boolean scanAccepted(UUID attempt) {
        var a =
                db.one(
                        "select a.scan_result,a.scan_evidence,j.sha256 from processing_attempts a join jobs j"
                                + " on j.id=a.job_id where a.id=?",
                        attempt);
        String result = (String) a.get("scan_result");
        if (!scanner.accepted(result)) return false;
        if (!"WINDOWED_MP4_V1".equals(result)) return true;
        try {
            var e =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(a.get("scan_evidence").toString());
            return e.path("complete").asBoolean()
                    && "clamav-windowed-mp4-v1".equals(e.path("policy").asText())
                    && e.path("sourceBytes").asLong() > 0
                    && e.path("sourceBytes").asLong() == e.path("coveredBytes").asLong()
                    && e.path("windows").asInt() > 0
                    && e.path("sha256").asText().matches("[a-f0-9]{64}")
                    && e.path("sha256").asText().equals(a.get("sha256"));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    @PutMapping("/videos/{id}/legal-hold")
    @Transactional
    public void hold(
            @AuthenticationPrincipal Jwt j, @PathVariable UUID id, @Valid @RequestBody Hold h) {
        reviewer(j);
        db.one("select id from videos where id=? for update", id);
        Db.require(
                db.sql.queryForObject(
                        "select count(*) from erasure_jobs where video_id=? and state='DONE'",
                        Integer.class,
                        id)
                        == 0,
                "Already erased");
        db.sql.update("update videos set legal_hold=?,version=version+1 where id=?", h.held(), id);
        db.event(id, "LegalHoldChanged", "{}");
    }

    public record Decision(
            @NotNull UUID attempt,
            @Pattern(regexp = "APPROVED|REJECTED") @NotNull String decision,
            @NotBlank @Size(max = 2000) String reason) {
    }

    public record Hold(boolean held) {
    }
}
