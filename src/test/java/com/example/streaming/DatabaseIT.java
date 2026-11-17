package com.example.streaming;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration")
@Testcontainers
class DatabaseIT {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    static JdbcTemplate db;

    @BeforeAll
    static void setup() {
        Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .load()
                .migrate();
        db =
                new JdbcTemplate(
                        new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    @Test
    void preventsPartialPublication() {
        db.update("insert into accounts(id,display_name) values('a','A') on conflict do nothing");
        assertThrows(
                Exception.class,
                () ->
                        db.update(
                                "insert into videos(id,owner_id,title,visibility,published)"
                                        + " values(?,'a','t','PUBLIC',true)",
                                UUID.randomUUID()));
    }

    @Test
    void likesAreUniqueUnderConcurrency() throws Exception {
        db.update("insert into accounts(id,display_name) values('b','B') on conflict do nothing");
        UUID id = UUID.randomUUID();
        db.update("insert into videos(id,owner_id,title,visibility) values(?,'b','t','PUBLIC')", id);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++)
                tasks.add(
                        pool.submit(
                                () ->
                                        db.update(
                                                "insert into video_likes(user_id,video_id) values('b',?) on conflict do"
                                                        + " nothing",
                                                id)));
            for (var f : tasks) f.get();
        }
        assertEquals(
                1,
                db.queryForObject("select count(*) from video_likes where video_id=?", Integer.class, id));
    }
}
