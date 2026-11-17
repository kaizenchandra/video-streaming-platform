package com.example.streaming.identity;

import com.example.streaming.shared.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1")
public class Profiles {
    private final Db db;
    private final Identity identity;
    private final ObjectMapper json;

    public Profiles(Db d, Identity i, ObjectMapper j) {
        db = d;
        identity = i;
        json = j;
    }

    @GetMapping("/users/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt j) throws Exception {
        String u = identity.user(j);
        var a =
                db.one(
                        "select id,display_name,preferences::text,profile_version,created_at from accounts"
                                + " where id=?",
                        u);
        a.put("preferences", json.readTree((String) a.get("preferences")));
        a.put("roles", Objects.requireNonNullElse(j.getClaimAsStringList("roles"), List.of()));
        return a;
    }

    @PatchMapping("/users/me")
    public Map<String, Object> update(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("If-Match") long version,
            @Valid @RequestBody Profile p)
            throws Exception {
        String u = identity.user(j);
        Db.require(
                db.sql.update(
                        "update accounts set"
                                + " display_name=?,preferences=?::jsonb,profile_version=profile_version+1 where"
                                + " id=? and active and profile_version=?",
                        p.displayName(),
                        json.writeValueAsString(p.preferences()),
                        u,
                        version)
                        == 1,
                "Stale profile version");
        return me(j);
    }

    @PutMapping("/users/me/channel")
    public Map<String, Object> channel(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("If-Match") long version,
            @Valid @RequestBody Channel c) {
        String u = identity.user(j);
        if (version == -1) {
            db.sql.update(
                    "insert into channels(owner_id,handle,name,description) values(?,?,?,?)",
                    u,
                    c.handle(),
                    c.name(),
                    c.description());
        } else
            Db.require(
                    db.sql.update(
                            "update channels set handle=?,name=?,description=?,version=version+1 where"
                                    + " owner_id=? and version=?",
                            c.handle(),
                            c.name(),
                            c.description(),
                            u,
                            version)
                            == 1,
                    "Stale channel version");
        return db.one("select * from channels where owner_id=?", u);
    }

    @GetMapping("/channels/{handle}")
    public Map<String, Object> channel(@AuthenticationPrincipal Jwt j, @PathVariable String handle) {
        identity.user(j);
        return db.one(
                "select c.* from channels c join accounts a on a.id=c.owner_id where c.handle=? and"
                        + " a.active",
                handle);
    }

    @GetMapping("/channels/{handle}/videos")
    public List<Map<String, Object>> channelVideos(
            @AuthenticationPrincipal Jwt j, @PathVariable String handle) {
        identity.user(j);
        return db.sql.queryForList(
                "select v.id,v.title,v.duration,v.created_at from videos v join channels c on"
                        + " c.owner_id=v.owner_id join accounts a on a.id=c.owner_id where c.handle=? and"
                        + " a.active and v.published and not v.deleted and v.moderation_status='APPROVED' and"
                        + " v.visibility='PUBLIC' order by v.created_at desc,v.id desc limit 100",
                handle);
    }

    @PostMapping("/users/me/deactivate")
    @Transactional
    public void deactivate(@AuthenticationPrincipal Jwt j) {
        String u = identity.user(j);
        db.sql.update(
                "update accounts set active=false,profile_version=profile_version+1 where id=?", u);
        db.sql.update(
                "update videos set published=false,version=version+1 where owner_id=? and not deleted", u);
        db.sql.update("update playback_sessions set expires_at=now() where user_id=?", u);
    }

    public record Preferences(
            @Pattern(regexp = "[a-z]{2}(-[A-Z]{2})?") @NotNull String language,
            boolean autoplay,
            @Pattern(regexp = "dark|light|system") @NotNull String theme) {
    }

    public record Profile(
            @NotBlank @Size(max = 100) String displayName, @NotNull @Valid Preferences preferences) {
    }

    public record Channel(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_-]{2,39}") String handle,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Size(max = 2000) String description) {
    }
}
