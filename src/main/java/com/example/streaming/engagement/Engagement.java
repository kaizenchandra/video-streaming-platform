package com.example.streaming.engagement;

import com.example.streaming.identity.Identity;
import com.example.streaming.shared.Db;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/videos/{id}")
public class Engagement {
    private final Db db;
    private final Identity identity;

    public Engagement(Db d, Identity i) {
        db = d;
        identity = i;
    }

    private String allowed(UUID id, Jwt j) {
        String u = identity.user(j);
        db.readable(db.video(id), u);
        return u;
    }

    @PutMapping("/likes/me")
    public void like(@PathVariable UUID id, @AuthenticationPrincipal Jwt j) {
        db.sql.update(
                "insert into video_likes(user_id,video_id) values(?,?) on conflict do nothing",
                allowed(id, j),
                id);
    }

    @DeleteMapping("/likes/me")
    public void unlike(@PathVariable UUID id, @AuthenticationPrincipal Jwt j) {
        db.sql.update("delete from video_likes where user_id=? and video_id=?", allowed(id, j), id);
    }

    @PutMapping("/watchlist/me")
    public void save(@PathVariable UUID id, @AuthenticationPrincipal Jwt j) {
        db.sql.update(
                "insert into watchlist(user_id,video_id) values(?,?) on conflict do nothing",
                allowed(id, j),
                id);
    }

    @PostMapping("/comments")
    public Map<String, Object> comment(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt j, @Valid @RequestBody Comment c) {
        String u = allowed(id, j);
        UUID cid = UUID.randomUUID();
        db.sql.update(
                "insert into comments(id,video_id,user_id,body) values(?,?,?,?)", cid, id, u, c.body());
        return Map.of("id", cid, "body", c.body());
    }

    @GetMapping("/comments")
    public List<Map<String, Object>> comments(@PathVariable UUID id, @AuthenticationPrincipal Jwt j) {
        allowed(id, j);
        return db.sql.queryForList(
                "select * from comments where video_id=? order by created_at desc limit 100", id);
    }

    @DeleteMapping("/comments/{comment}")
    public void remove(
            @PathVariable UUID id, @PathVariable UUID comment, @AuthenticationPrincipal Jwt j) {
        String u = allowed(id, j);
        db.sql.update(
                "delete from comments where id=? and video_id=? and (user_id=? or exists(select 1 from"
                        + " videos where id=? and owner_id=?))",
                comment,
                id,
                u,
                id,
                u);
    }

    public record Comment(@NotBlank @Size(max = 2000) String body) {
    }
}
