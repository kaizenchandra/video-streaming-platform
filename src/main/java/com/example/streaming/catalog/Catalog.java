package com.example.streaming.catalog;

import com.example.streaming.identity.Identity;
import com.example.streaming.shared.Db;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class Catalog {
    private final Db db;
    private final Identity identity;
    private final com.example.streaming.retention.Erasure erasure;
    private final boolean autoApprove;
    private final com.example.streaming.recommendation.Recommendations recommendations;

    public Catalog(
            Db d,
            Identity i,
            com.example.streaming.recommendation.Recommendations rec,
            com.example.streaming.retention.Erasure erasure,
            @org.springframework.beans.factory.annotation.Value("${media.moderation.auto-approve:false}")
            boolean autoApprove) {
        db = d;
        identity = i;
        recommendations = rec;
        this.erasure = erasure;
        this.autoApprove = autoApprove;
    }

    @PostMapping("/videos")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String, Object> create(
            @AuthenticationPrincipal Jwt j, @Valid @RequestBody Metadata m) {
        String u = identity.user(j);
        UUID id = UUID.randomUUID();
        Db.require(!m.published(), "Upload and process before publishing");
        db.sql.update(
                "insert into videos(id,owner_id,title,description,category,tags,visibility)"
                        + " values(?,?,?,?,?,?,?)",
                id,
                u,
                m.title(),
                Objects.toString(m.description(), ""),
                m.category(),
                Objects.toString(m.tags(), ""),
                m.visibility());
        return db.video(id);
    }

    @GetMapping("/videos/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        var v = db.video(id);
        db.readable(v, identity.user(j));
        v.remove("search");
        return v;
    }

    @PatchMapping("/videos/{id}")
    @Transactional
    public Map<String, Object> patch(
            @AuthenticationPrincipal Jwt j,
            @PathVariable UUID id,
            @Valid @RequestBody Metadata m,
            @RequestHeader("If-Match") long version) {
        var v = db.own(id, identity.user(j));
        Db.require(!m.published() || "READY".equals(v.get("state")), "Media not ready");
        boolean edited =
                !m.title().equals(v.get("title"))
                        || !Objects.toString(m.description(), "").equals(v.get("description"))
                        || !Objects.toString(m.tags(), "").equals(v.get("tags"))
                        || !m.category().equals(v.get("category"));
        String moderation = edited && !autoApprove ? "PENDING" : (String) v.get("moderation_status");
        Db.require(!m.published() || "APPROVED".equals(moderation), "Moderation approval required");
        int n =
                db.sql.update(
                        "update videos set"
                                + " title=?,description=?,category=?,tags=?,visibility=?,moderation_status=?,published=?,published_at=case"
                                + " when ? then coalesce(published_at,now()) else published_at"
                                + " end,version=version+1 where id=? and version=? and not deleted",
                        m.title(),
                        Objects.toString(m.description(), ""),
                        m.category(),
                        Objects.toString(m.tags(), ""),
                        m.visibility(),
                        moderation,
                        m.published(),
                        m.published(),
                        id,
                        version);
        Db.require(n == 1, "Stale metadata version");
        return db.video(id);
    }

    @DeleteMapping("/videos/{id}")
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        erasure.request(id, identity.user(j));
    }

    @GetMapping({"/videos", "/search/videos", "/videos/trending"})
    public List<Map<String, Object>> list(
            @AuthenticationPrincipal Jwt j,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String creator,
            @RequestParam(defaultValue = "86400") double maxDuration,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) UUID before) {
        identity.user(j);
        return db.sql.queryForList(
                "select"
                        + " v.id,v.title,v.description,v.category,v.owner_id,v.duration,v.version,v.created_at,coalesce(w.views,0)"
                        + " as views from videos v left join video_views w on w.video_id=v.id where v.published"
                        + " and not v.deleted and v.moderation_status='APPROVED' and exists(select 1 from"
                        + " accounts a where a.id=v.owner_id and a.active) and v.visibility='PUBLIC' and (?=''"
                        + " or v.search @@ plainto_tsquery('english',?)) and (?='' or v.category=?) and (?=''"
                        + " or v.owner_id=?) and coalesce(v.duration,0)<=? and (?::uuid is null or"
                        + " (v.created_at,v.id)<(select created_at,id from videos where id=?::uuid)) order by"
                        + " v.created_at desc,v.id desc limit ?",
                q,
                q,
                category,
                category,
                creator,
                creator,
                maxDuration,
                before,
                before,
                Math.clamp(limit, 1, 100));
    }

    @GetMapping("/videos/recommendations")
    public List<Map<String, Object>> recommend(
            @AuthenticationPrincipal Jwt j, @RequestParam(defaultValue = "20") int limit) {
        return recommendations.ranked(identity.user(j), limit);
    }

    @GetMapping("/users/me/videos")
    public List<Map<String, Object>> mine(@AuthenticationPrincipal Jwt j) {
        return db.sql.queryForList(
                "select id,title,state,published,version,visibility,moderation_status from videos where"
                        + " owner_id=? and not deleted order by created_at desc limit 100",
                identity.user(j));
    }

    public record Metadata(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 5000) String description,
            @NotBlank @Size(max = 80) String category,
            @Size(max = 1000) String tags,
            @Pattern(regexp = "PUBLIC|UNLISTED|PRIVATE") @NotNull String visibility,
            boolean published) {
    }
}
