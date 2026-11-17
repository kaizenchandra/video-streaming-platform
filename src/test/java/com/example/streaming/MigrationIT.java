package com.example.streaming;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
@Testcontainers
class MigrationIT {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void upgradePreservesPublishedDataAndQueuesOldTombstones() {
        var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(ds).target("1").load().migrate();
        var db = new JdbcTemplate(ds);
        db.update("insert into accounts(id,display_name) values('legacy','Legacy')");
        UUID deleted = UUID.randomUUID(),
                live = UUID.randomUUID(),
                job = UUID.randomUUID(),
                attempt = UUID.randomUUID();
        db.update(
                "insert into videos(id,owner_id,title,visibility,deleted) values(?,'legacy','Old"
                        + " deletion','PRIVATE',true)",
                deleted);
        db.update(
                "insert into videos(id,owner_id,title,visibility) values(?,'legacy','Existing"
                        + " publication','PUBLIC')",
                live);
        db.update(
                "insert into jobs(id,video_id,source_key,sha256,state)"
                        + " values(?,?,'legacy','hash','SUCCEEDED')",
                job,
                live);
        db.update(
                "insert into processing_attempts(id,job_id,state) values(?,?,'SUCCEEDED')", attempt, job);
        db.update(
                "update videos set state='READY',current_attempt=?,published=true where id=?",
                attempt,
                live);
        var upgrade = Flyway.configure().dataSource(ds).load();
        upgrade.migrate();
        upgrade.migrate();
        assertEquals(
                true, db.queryForObject("select published from videos where id=?", Boolean.class, live));
        assertEquals(
                "APPROVED",
                db.queryForObject("select moderation_status from videos where id=?", String.class, live));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from erasure_jobs where video_id=? and state='PENDING' and"
                                + " not_before>now()+interval '23 hours'",
                        Integer.class,
                        deleted));
    }
}
