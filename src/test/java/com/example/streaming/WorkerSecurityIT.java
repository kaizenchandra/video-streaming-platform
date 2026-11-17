package com.example.streaming;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
        properties = {
                "media.worker=true",
                "media.cdn.origin=https://stream.example.com",
                "media.cdn.key-id=test-key",
                "media.cdn.private-key-file=/not-mounted-in-worker"
        })
class WorkerSecurityIT {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Test
    void controlAndMediaRoutesDeniedEvenWithJwt() throws Exception {
        mvc.perform(get("/api/v1/videos").with(jwt())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/media-auth").header("X-Original-URI", "/media/test").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void readinessAvailableWithoutCdnPrivateKey() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
