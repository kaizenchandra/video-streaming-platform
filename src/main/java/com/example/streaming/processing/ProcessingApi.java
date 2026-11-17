package com.example.streaming.processing;

import com.example.streaming.identity.Identity;
import com.example.streaming.shared.Db;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/videos/{id}")
public class ProcessingApi {
    private final Db db;
    private final Identity identity;

    public ProcessingApi(Db d, Identity i) {
        db = d;
        identity = i;
    }

    @GetMapping("/processing-status")
    public List<Map<String, Object>> status(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        db.own(id, identity.user(j));
        return db.sql.queryForList(
                "select id,state,attempts,error from jobs where video_id=? order by created_at desc limit"
                        + " 10",
                id);
    }

    @PostMapping("/reprocess")
    @Transactional
    public void retry(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        db.own(id, identity.user(j));
        db.one("select id from videos where id=? and not deleted for update", id);
        var source =
                db.one(
                        "select object_key,sha256 from uploads where video_id=? and state='COMPLETED' order by"
                                + " created_at desc limit 1",
                        id);
        db.sql.update(
                "insert into jobs(id,video_id,source_key,sha256) values(?,?,?,?)",
                UUID.randomUUID(),
                id,
                source.get("object_key"),
                source.get("sha256"));
    }
}
