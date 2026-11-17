package com.example.streaming.retention;

import com.example.streaming.identity.Identity;
import com.example.streaming.shared.Db;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
public class ErasureApi {
    private final Db db;
    private final Identity identity;

    public ErasureApi(Db db, Identity identity) {
        this.db = db;
        this.identity = identity;
    }

    @GetMapping("/api/v1/videos/{id}/erasure-status")
    public Map<String, Object> status(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        String user = identity.user(j);
        var v = db.one("select owner_id,legal_hold from videos where id=?", id);
        if (!user.equals(v.get("owner_id")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner required");
        var result =
                db.one(
                        "select state,not_before,attempts,last_error,completed_at from erasure_jobs where"
                                + " video_id=?",
                        id);
        result.put("legalHold", v.get("legal_hold"));
        result.put(
                "scratchPending",
                db.sql.queryForObject(
                        "select count(*) from processing_attempts a join jobs j on j.id=a.job_id where"
                                + " j.video_id=? and a.scratch_volume is not null and a.scratch_purged_at is null",
                        Long.class,
                        id));
        return result;
    }
}
