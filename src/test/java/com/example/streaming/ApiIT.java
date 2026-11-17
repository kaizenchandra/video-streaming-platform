package com.example.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {"media.dev-auth=true"})
@AutoConfigureMockMvc
class ApiIT {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    com.example.streaming.shared.Db db;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    String create(String visibility) throws Exception {
        return json.readTree(
                        mvc.perform(
                                        post("/api/v1/videos")
                                                .with(jwt().jwt(j -> j.subject("owner")))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"title\":\"Private example\",\"category\":\"test\",\"visibility\":\""
                                                                + visibility
                                                                + "\",\"published\":false}"))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .path("id")
                .asText();
    }

    @Test
    void bearerRequired() throws Exception {
        mvc.perform(get("/api/v1/videos")).andExpect(status().isUnauthorized());
    }

    @Test
    void privateHiddenAndOwnerEnforced() throws Exception {
        String id = create("PRIVATE");
        mvc.perform(get("/api/v1/videos/" + id).with(jwt().jwt(j -> j.subject("stranger"))))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/videos/" + id).with(jwt().jwt(j -> j.subject("stranger"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotPublishUnprocessed() throws Exception {
        String id = create("PUBLIC");
        mvc.perform(
                        patch("/api/v1/videos/" + id)
                                .with(jwt().jwt(j -> j.subject("owner")))
                                .header("If-Match", "0")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"title\":\"example\",\"category\":\"test\",\"visibility\":\"PUBLIC\",\"published\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void staleVersionRejected() throws Exception {
        String id = create("PRIVATE");
        mvc.perform(
                        patch("/api/v1/videos/" + id)
                                .with(jwt().jwt(j -> j.subject("owner")))
                                .header("If-Match", "999")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"title\":\"changed\",\"category\":\"test\",\"visibility\":\"PRIVATE\",\"published\":false}"))
                .andExpect(status().isConflict());
    }

    @Test
    void missingMediaCookieDenied() throws Exception {
        mvc.perform(get("/internal/media-auth").header("X-Original-URI", "/media/videos/x/master.m3u8"))
                .andExpect(status().isForbidden());
    }

    @Test
    void inputValidation() throws Exception {
        mvc.perform(
                        post("/api/v1/videos")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"\",\"category\":\"x\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profilesAndChannelsUseOptimisticVersions() throws Exception {
        String user = "profile-" + java.util.UUID.randomUUID();
        mvc.perform(get("/api/v1/users/me").with(jwt().jwt(j -> j.subject(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile_version").value(0));
        String profile =
                "{\"displayName\":\"Creator"
                        + " Name\",\"preferences\":{\"language\":\"en\",\"theme\":\"dark\",\"autoplay\":true}}";
        mvc.perform(
                        patch("/api/v1/users/me")
                                .with(jwt().jwt(j -> j.subject(user)))
                                .header("If-Match", "0")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(profile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences.theme").value("dark"));
        mvc.perform(
                        patch("/api/v1/users/me")
                                .with(jwt().jwt(j -> j.subject(user)))
                                .header("If-Match", "0")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(profile))
                .andExpect(status().isConflict());
        String handle = "c-" + java.util.UUID.randomUUID();
        String channel =
                "{\"handle\":\"" + handle + "\",\"name\":\"My channel\",\"description\":\"Synthetic\"}";
        mvc.perform(
                        put("/api/v1/users/me/channel")
                                .with(jwt().jwt(j -> j.subject(user)))
                                .header("If-Match", "-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(channel))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/channels/" + handle).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My channel"));
        mvc.perform(
                        put("/api/v1/users/me/channel")
                                .with(jwt().jwt(j -> j.subject("other-" + user)))
                                .header("If-Match", "-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(channel))
                .andExpect(status().isConflict());
    }

    @Test
    void accountDeactivationRejectsFurtherRequests() throws Exception {
        String user = "inactive-" + java.util.UUID.randomUUID();
        mvc.perform(post("/api/v1/users/me/deactivate").with(jwt().jwt(j -> j.subject(user))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/me").with(jwt().jwt(j -> j.subject(user))))
                .andExpect(status().isForbidden());
    }

    java.util.UUID ready(String id) {
        var video = java.util.UUID.fromString(id);
        var job = java.util.UUID.randomUUID();
        var attempt = java.util.UUID.randomUUID();
        db.sql.update(
                "insert into jobs(id,video_id,source_key,sha256,state) values(?,?,?,'test','SUCCEEDED')",
                job,
                video,
                "test");
        db.sql.update(
                "insert into processing_attempts(id,job_id,state,scan_result)"
                        + " values(?,?,'SUCCEEDED','CLEAN')",
                attempt,
                job);
        db.sql.update(
                "update videos set state='READY',current_attempt=?,duration=8 where id=?", attempt, video);
        return attempt;
    }

    String decision(java.util.UUID attempt) {
        return "{\"attempt\":\""
                + attempt
                + "\",\"decision\":\"APPROVED\",\"reason\":\"Manual policy review\"}";
    }

    @Test
    void ownerCannotSelfApproveAndStaleReviewRejected() throws Exception {
        String id = create("PUBLIC");
        var attempt = ready(id);
        mvc.perform(
                        post("/api/v1/videos/" + id + "/moderation-decisions")
                                .with(jwt().jwt(j -> j.subject("owner")))
                                .header("If-Match", "0")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(decision(attempt)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/videos/" + id + "/moderation-decisions")
                                .with(
                                        jwt()
                                                .jwt(
                                                        j ->
                                                                j.subject("reviewer")
                                                                        .claim("roles", java.util.List.of("MODERATOR"))))
                                .header("If-Match", "999")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(decision(attempt)))
                .andExpect(status().isConflict());
    }

    @Test
    void approvedVersionCanPublishAndEditRequiresNewReview() throws Exception {
        String id = create("PUBLIC");
        var attempt = ready(id);
        String original =
                "{\"title\":\"Private"
                        + " example\",\"description\":\"\",\"category\":\"test\",\"tags\":\"\",\"visibility\":\"PUBLIC\",\"published\":true}";
        mvc.perform(
                        patch("/api/v1/videos/" + id)
                                .with(jwt().jwt(j -> j.subject("owner")))
                                .header("If-Match", "0")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(original))
                .andExpect(status().isConflict());
        mvc.perform(
                        post("/api/v1/videos/" + id + "/moderation-decisions")
                                .with(
                                        jwt()
                                                .jwt(
                                                        j ->
                                                                j.subject("reviewer")
                                                                        .claim("roles", java.util.List.of("MODERATOR"))))
                                .header("If-Match", "0")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(decision(attempt)))
                .andExpect(status().isOk());
        mvc.perform(
                        patch("/api/v1/videos/" + id)
                                .with(jwt().jwt(j -> j.subject("owner")))
                                .header("If-Match", "1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(original))
                .andExpect(status().isOk());
        mvc.perform(
                        patch("/api/v1/videos/" + id)
                                .with(jwt().jwt(j -> j.subject("owner")))
                                .header("If-Match", "2")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        original.replace("Private example", "Edited title").replace("true", "false")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderation_status").value("PENDING"));
        mvc.perform(get("/api/v1/videos/" + id).with(jwt().jwt(j -> j.subject("viewer"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void moderatorPreviewIsScopedAndDoesNotRecordViews() throws Exception {
        String id = create("PRIVATE");
        ready(id);
        mvc.perform(
                        post("/api/v1/videos/" + id + "/review-playback-sessions")
                                .with(jwt().jwt(j -> j.subject("owner"))))
                .andExpect(status().isForbidden());
        var response =
                mvc.perform(
                                post("/api/v1/videos/" + id + "/review-playback-sessions")
                                        .with(
                                                jwt()
                                                        .jwt(
                                                                j ->
                                                                        j.subject("review-preview")
                                                                                .claim("roles", java.util.List.of("MODERATOR")))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        var body = json.readTree(response.getContentAsString());
        String session = body.path("sessionId").asText();
        String manifest = body.path("manifest").asText();
        mvc.perform(
                        get("/internal/media-auth")
                                .cookie(new jakarta.servlet.http.Cookie("playback", session))
                                .header("X-Original-URI", manifest))
                .andExpect(status().isOk());
        mvc.perform(
                        get("/internal/media-auth")
                                .cookie(new jakarta.servlet.http.Cookie("playback", session))
                                .header(
                                        "X-Original-URI", manifest.replace(id, java.util.UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put("/api/v1/videos/" + id + "/watch-progress")
                                .with(jwt().jwt(j -> j.subject("review-preview")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                java.util.Map.of(
                                                        "sessionId",
                                                        session,
                                                        "sequence",
                                                        1,
                                                        "position",
                                                        7,
                                                        "completed",
                                                        false))))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(
                0,
                db.sql.queryForObject(
                        "select count(*) from watch_progress where video_id=?",
                        Integer.class,
                        java.util.UUID.fromString(id)));
    }

    @Test
    void activeScannerBudgetRejectsOversizedInitiation() throws Exception {
        String id = create("PUBLIC");
        mvc.perform(
                        post("/api/v1/videos/uploads")
                                .with(jwt().jwt(j -> j.subject("owner")))
                                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                java.util.Map.of(
                                                        "videoId", id, "bytes", 104857601, "sha256", "0".repeat(64)))))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void moderatorQueueExposesScanPolicyWhileOwnerCannotReadQueue() throws Exception {
        String id = create("PRIVATE");
        ready(id);
        mvc.perform(get("/api/v1/moderation/videos").with(jwt().jwt(j -> j.subject("owner"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/v1/moderation/videos")
                                .with(
                                        jwt()
                                                .jwt(
                                                        j ->
                                                                j.subject("reviewer")
                                                                        .claim("roles", java.util.List.of("MODERATOR")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scan_result").value("CLEAN"));
    }
}
