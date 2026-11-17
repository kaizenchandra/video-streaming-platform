package com.example.streaming;

import com.example.streaming.analytics.EventConsumer;
import com.example.streaming.identity.Identity;
import com.example.streaming.processing.Encoder;
import com.example.streaming.processing.MediaScanner;
import com.example.streaming.processing.ScratchSpace;
import com.example.streaming.processing.Worker;
import com.example.streaming.retention.Erasure;
import com.example.streaming.retention.GarbageCollector;
import com.example.streaming.shared.Db;
import com.example.streaming.upload.Storage;
import com.example.streaming.upload.Uploads;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Tag("integration")
@Testcontainers
class RecoveryIT {
    @org.testcontainers.junit.jupiter.Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @org.testcontainers.junit.jupiter.Container
    static GenericContainer<?> s3 =
            new GenericContainer<>(DockerImageName.parse("localstack/localstack:4.10.0"))
                    .withEnv("SERVICES", "s3")
                    .withExposedPorts(4566)
                    .waitingFor(Wait.forHttp("/_localstack/health").forStatusCode(200));

    static Db db;
    static TransactionTemplate tx;
    static Storage storage;
    @TempDir
    Path scratch;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        db = new Db(new JdbcTemplate(ds));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String endpoint = "http://" + s3.getHost() + ":" + s3.getMappedPort(4566);
        storage =
                new Storage(
                        endpoint, endpoint, "recovery-media", "test", "test", "static", "us-east-1", true, "");
        storage.client.createBucket(b -> b.bucket(storage.bucket));
        storage.client.putBucketVersioning(
                b ->
                        b.bucket(storage.bucket)
                                .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
    }

    @AfterAll
    static void close() {
        storage.close();
    }

    @BeforeEach
    void isolate() {
        db.sql.execute("truncate events,accounts cascade");
    }

    Uploads uploads(Storage s) {
        return new Uploads(db, new Identity(db), s, tx);
    }

    Jwt jwt(String owner) {
        return Jwt.withTokenValue("test").header("alg", "none").subject(owner).build();
    }

    Fixture uploaded() throws Exception {
        String owner = "recovery-" + UUID.randomUUID();
        new Identity(db).user(jwt(owner));
        UUID video = UUID.randomUUID();
        db.sql.update(
                "insert into videos(id,owner_id,title,visibility) values(?,?,?,'PUBLIC')",
                video,
                owner,
                "Fixture");
        byte[] bytes = "synthetic source bytes".getBytes();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var u =
                uploads(storage)
                        .start(
                                jwt(owner),
                                UUID.randomUUID().toString(),
                                new Uploads.Start(video, bytes.length, hash));
        storage.client.uploadPart(
                b ->
                        b.bucket(storage.bucket)
                                .key((String) u.get("object_key"))
                                .uploadId((String) u.get("multipart_id"))
                                .partNumber(1),
                RequestBody.fromBytes(bytes));
        return new Fixture(
                video,
                owner,
                (UUID) u.get("id"),
                (String) u.get("object_key"),
                (String) u.get("multipart_id"));
    }

    Fixture queued() throws Exception {
        var f = uploaded();
        uploads(storage).finish(f.upload());
        return f;
    }

    Erasure erasure(Storage s) {
        return new Erasure(db, s, tx, 1200, false);
    }

    void due(UUID video) {
        db.sql.update(
                "update erasure_jobs set not_before=now()-interval '1 second' where video_id=?", video);
    }

    void eraseToCompletion(Erasure e, UUID video) {
        for (int i = 0;
             i < 10
                     && !"DONE"
                     .equals(
                             db.one("select state from erasure_jobs where video_id=?", video).get("state"));
             i++)
            e.runOne();
        assertEquals(
                "DONE", db.one("select state from erasure_jobs where video_id=?", video).get("state"));
    }

    Encoder fakeEncoder() throws Exception {
        var e = mock(Encoder.class);
        when(e.encode(any(), any()))
                .thenAnswer(
                        invocation -> {
                            Path out = invocation.getArgument(1);
                            Files.createDirectories(out);
                            Files.writeString(out.resolve("master.m3u8"), "#EXTM3U\n");
                            Files.writeString(out.resolve("cover.jpg"), "synthetic output");
                            return new Encoder.Result(8, List.of(240, 360));
                        });
        return e;
    }

    Worker worker(Encoder e) {
        return new Worker(
                db, storage, e, tx, scratch.toString(), new MediaScanner("disabled", "", 0), true, 1024);
    }

    @Test
    void completeRecoversAfterObjectCommitAndDatabaseRollback() throws Exception {
        var f = uploaded();
        tx.executeWithoutResult(
                t -> {
                    uploads(storage).finish(f.upload());
                    t.setRollbackOnly();
                });
        assertEquals(
                "UPLOADING", db.one("select state from uploads where id=?", f.upload()).get("state"));
        assertTrue(storage.head(f.key()).contentLength() > 0);
        uploads(storage).finish(f.upload());
        assertEquals(
                1,
                db.sql.queryForObject(
                        "select count(*) from jobs where video_id=?", Integer.class, f.video()));
    }

    @Test
    void concurrentCompleteCreatesOneJob() throws Exception {
        var f = uploaded();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> uploads(storage).finish(f.upload()));
            var b = pool.submit(() -> uploads(storage).finish(f.upload()));
            assertEquals("COMPLETED", a.get().get("state"));
            assertEquals("COMPLETED", b.get().get("state"));
        }
        assertEquals(
                1,
                db.sql.queryForObject(
                        "select count(*) from jobs where video_id=?", Integer.class, f.video()));
    }

    @Test
    void storeFailurePreservesReservationAndRetries() throws Exception {
        var f = uploaded();
        var failing = spy(storage);
        doThrow(S3Exception.builder().statusCode(503).message("Injected outage").build())
                .doCallRealMethod()
                .when(failing)
                .head(f.key());
        assertThrows(S3Exception.class, () -> uploads(failing).finish(f.upload()));
        assertEquals(
                0,
                db.sql.queryForObject(
                        "select count(*) from jobs where video_id=?", Integer.class, f.video()));
        assertTrue(
                db.sql.queryForObject("select reserved from accounts where id=?", Long.class, f.owner())
                        > 0);
        uploads(failing).finish(f.upload());
        assertEquals(
                0L,
                db.sql.queryForObject("select reserved from accounts where id=?", Long.class, f.owner()));
    }

    @Test
    void erasesAllVersionsAndDeleteMarkersButNotNeighbor() throws Exception {
        UUID id = UUID.randomUUID();
        String key = "videos/" + id + "/segment", neighbor = "videos/" + UUID.randomUUID() + "/segment";
        for (int i = 0; i < 3; i++)
            storage.client.putObject(
                    b -> b.bucket(storage.bucket).key(key), RequestBody.fromString("version" + i));
        storage.client.putObject(
                b -> b.bucket(storage.bucket).key(neighbor), RequestBody.fromString("keep"));
        storage.client.deleteObject(b -> b.bucket(storage.bucket).key(key));
        assertFalse(storage.erasePage("videos/" + id + "/"));
        assertTrue(storage.erasePage("videos/" + id + "/"));
        assertEquals(4L, storage.head(neighbor).contentLength());
    }

    @Test
    void legalHoldAndErasureRetryPreserveQuotaAccounting() throws Exception {
        var f = queued();
        var e = erasure(storage);
        e.request(f.video(), f.owner());
        due(f.video());
        db.sql.update("update videos set legal_hold=true where id=?", f.video());
        e.runOne();
        assertTrue(storage.head(f.key()).contentLength() > 0);
        db.sql.update("update videos set legal_hold=false where id=?", f.video());
        var failing = spy(storage);
        doThrow(S3Exception.builder().statusCode(503).message("Injected delete outage").build())
                .when(failing)
                .erasePage("videos/" + f.video() + "/");
        erasure(failing).runOne();
        assertNotNull(
                db.one("select last_error from erasure_jobs where video_id=?", f.video())
                        .get("last_error"));
        tx.executeWithoutResult(
                t -> {
                    e.runOne();
                    t.setRollbackOnly();
                });
        assertTrue(
                db.sql.queryForObject("select used from accounts where id=?", Long.class, f.owner()) > 0);
        eraseToCompletion(e, f.video());
        e.runOne();
        assertEquals(
                0L, db.sql.queryForObject("select used from accounts where id=?", Long.class, f.owner()));
        assertEquals(
                "CANCELLED", db.one("select state from jobs where video_id=?", f.video()).get("state"));
    }

    @Test
    void deletionDuringEncodeFencesOutputAndCannotRequeue() throws Exception {
        var f = queued();
        var encoder = fakeEncoder();
        doAnswer(
                invocation -> {
                    erasure(storage).request(f.video(), f.owner());
                    Path out = invocation.getArgument(1);
                    Files.createDirectories(out);
                    Files.writeString(out.resolve("master.m3u8"), "#EXTM3U\n");
                    return new Encoder.Result(8, List.of(240, 360));
                })
                .when(encoder)
                .encode(any(), any());
        worker(encoder).poll();
        assertEquals(
                "CANCELLED", db.one("select state from jobs where video_id=?", f.video()).get("state"));
        assertNull(
                db.one("select current_attempt from videos where id=?", f.video()).get("current_attempt"));
        assertTrue(storage.erasePage("videos/" + f.video() + "/"));
    }

    @Test
    void expiredLeaseRejectsOldWorkerPublication() throws Exception {
        var f = queued();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var slow = fakeEncoder();
        doAnswer(
                invocation -> {
                    Path out = invocation.getArgument(1);
                    Files.createDirectories(out);
                    Files.writeString(out.resolve("master.m3u8"), "old");
                    entered.countDown();
                    assertTrue(resume.await(20, TimeUnit.SECONDS));
                    return new Encoder.Result(8, List.of(240, 360));
                })
                .when(slow)
                .encode(any(), any());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = pool.submit(() -> worker(slow).poll());
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            UUID oldAttempt =
                    (UUID) db.one("select attempt from jobs where video_id=?", f.video()).get("attempt");
            db.sql.update(
                    "update jobs set lease_until=now()-interval '1 second' where video_id=?", f.video());
            worker(fakeEncoder()).poll();
            UUID winner = (UUID) db.video(f.video()).get("current_attempt");
            assertNotEquals(oldAttempt, winner);
            resume.countDown();
            old.get(20, TimeUnit.SECONDS);
            assertEquals(winner, db.video(f.video()).get("current_attempt"));
            assertEquals(
                    "SUCCEEDED", db.one("select state from jobs where video_id=?", f.video()).get("state"));
        }
    }

    @Test
    void failedMetadataCommitLeavesCollectableAttemptAndRetryWins() throws Exception {
        var f = queued();
        db.sql.execute(
                "create function reject_ready() returns trigger language plpgsql as $$ begin if"
                        + " NEW.state='READY' then raise exception 'Injected commit failure'; end if; return"
                        + " NEW; end $$");
        db.sql.execute(
                "create trigger reject_ready before update on videos for each row execute function"
                        + " reject_ready()");
        try {
            worker(fakeEncoder()).poll();
        } finally {
            db.sql.execute("drop trigger reject_ready on videos");
            db.sql.execute("drop function reject_ready()");
        }
        UUID oldAttempt =
                (UUID) db.one("select attempt from jobs where video_id=?", f.video()).get("attempt");
        assertNull(db.video(f.video()).get("current_attempt"));
        db.sql.update("update jobs set available_at=now() where video_id=?", f.video());
        worker(fakeEncoder()).poll();
        assertNotEquals(oldAttempt, db.video(f.video()).get("current_attempt"));
        db.sql.update(
                "update processing_attempts set started_at=now()-interval '8 days' where id=?", oldAttempt);
        var gc = new GarbageCollector(db, storage, tx, false);
        gc.runOne();
        gc.runOne();
        assertNotNull(
                db.one("select collected_at from processing_attempts where id=?", oldAttempt)
                        .get("collected_at"));
        assertTrue(
                storage
                        .head(
                                "videos/"
                                        + f.video()
                                        + "/"
                                        + db.video(f.video()).get("current_attempt")
                                        + "/master.m3u8")
                        .contentLength()
                        > 0);
    }

    @Test
    void outboxConsumerRollbackDoesNotInflateViews() throws Exception {
        var f = queued();
        db.event(f.video(), "QualifiedView", "{}");
        var consumer = new EventConsumer(db, tx);
        tx.executeWithoutResult(
                t -> {
                    consumer.consume();
                    t.setRollbackOnly();
                });
        consumer.consume();
        consumer.consume();
        assertEquals(
                1L,
                db.sql.queryForObject(
                        "select views from video_views where video_id=?", Long.class, f.video()));
    }

    @Test
    void liveSessionProtectsSupersededAttemptUntilExpiry() throws Exception {
        var f = queued();
        worker(fakeEncoder()).poll();
        UUID job = (UUID) db.one("select id from jobs where video_id=?", f.video()).get("id");
        UUID old = UUID.randomUUID(), session = UUID.randomUUID();
        db.sql.update(
                "insert into processing_attempts(id,job_id,started_at,state) values(?,?,now()-interval '8"
                        + " days','SUCCEEDED')",
                old,
                job);
        String key = "videos/" + f.video() + "/" + old + "/master.m3u8";
        storage.client.putObject(b -> b.bucket(storage.bucket).key(key), RequestBody.fromString("old"));
        db.sql.update(
                "insert into playback_sessions(id,video_id,user_id,attempt) values(?,?,?,?)",
                session,
                f.video(),
                f.owner(),
                old);
        var gc = new GarbageCollector(db, storage, tx, false);
        gc.runOne();
        assertEquals(3L, storage.head(key).contentLength());
        db.sql.update(
                "update playback_sessions set expires_at=now()-interval '1 second' where id=?", session);
        gc.runOne();
        gc.runOne();
        assertNotNull(
                db.one("select collected_at from processing_attempts where id=?", old).get("collected_at"));
    }

    @Test
    void infectedSourceIsBlockedWithoutRetryOrEncoding() throws Exception {
        var f = queued();
        var scanner = mock(MediaScanner.class);
        when(scanner.scan(any())).thenThrow(new MediaScanner.Infected());
        var encoder = fakeEncoder();
        new Worker(db, storage, encoder, tx, scratch.toString(), scanner, true, 1024).poll();
        assertEquals(
                "BLOCKED", db.one("select state from jobs where video_id=?", f.video()).get("state"));
        assertEquals("REJECTED", db.video(f.video()).get("moderation_status"));
        verify(encoder, never()).encode(any(), any());
    }

    @Test
    void scannerOutageFailsClosedAndBackoffPreventsTightRetry() throws Exception {
        var f = queued();
        var scanner = mock(MediaScanner.class);
        when(scanner.scan(any())).thenThrow(new java.io.IOException("Injected scanner outage"));
        var encoder = fakeEncoder();
        var w = new Worker(db, storage, encoder, tx, scratch.toString(), scanner, true, 1024);
        w.poll();
        w.poll();
        assertEquals(
                1,
                ((Number) db.one("select attempts from jobs where video_id=?", f.video()).get("attempts"))
                        .intValue());
        assertNull(db.video(f.video()).get("current_attempt"));
        verify(encoder, never()).encode(any(), any());
    }

    @Test
    void finalExpiredLeaseMovesJobAttemptAndVideoToTerminalFailure() throws Exception {
        var f = queued();
        UUID job = (UUID) db.one("select id from jobs where video_id=?", f.video()).get("id"),
                attempt = UUID.randomUUID();
        db.sql.update("insert into processing_attempts(id,job_id) values(?,?)", attempt, job);
        db.sql.update(
                "update jobs set attempts=3,state='RUNNING',attempt=?,lease_until=now()-interval '1 second'"
                        + " where id=?",
                attempt,
                job);
        worker(fakeEncoder()).poll();
        assertEquals("FAILED", db.one("select state from jobs where id=?", job).get("state"));
        assertEquals(
                "FAILED", db.one("select state from processing_attempts where id=?", attempt).get("state"));
        assertEquals("FAILED", db.video(f.video()).get("state"));
    }

    @Test
    void abortReleasesQuotaAndAllowsNewUploadForSameDraft() throws Exception {
        var f = uploaded();
        uploads(storage).abort(jwt(f.owner()), f.upload());
        uploads(storage).abort(jwt(f.owner()), f.upload());
        assertEquals("DRAFT", db.video(f.video()).get("state"));
        assertEquals(
                0L,
                db.sql.queryForObject("select reserved from accounts where id=?", Long.class, f.owner()));
        var retry =
                uploads(storage)
                        .start(
                                jwt(f.owner()),
                                UUID.randomUUID().toString(),
                                new Uploads.Start(f.video(), 21, "0".repeat(64)));
        assertEquals("UPLOADING", retry.get("state"));
    }

    @Test
    void ambiguousCompletionCannotBeAbortedAndUncharged() throws Exception {
        var f = uploaded();
        tx.executeWithoutResult(
                t -> {
                    uploads(storage).finish(f.upload());
                    t.setRollbackOnly();
                });
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> uploads(storage).abort(jwt(f.owner()), f.upload()));
        assertTrue(
                db.sql.queryForObject("select reserved from accounts where id=?", Long.class, f.owner())
                        > 0);
        uploads(storage).finish(f.upload());
    }

    @Test
    void abortedLateSourceIsCollectedWithoutTouchingNewUpload() throws Exception {
        var f = uploaded();
        uploads(storage).abort(jwt(f.owner()), f.upload());
        storage.client.putObject(
                b -> b.bucket(storage.bucket).key(f.key()), RequestBody.fromString("late source"));
        db.sql.update("update uploads set created_at=now()-interval '8 days' where id=?", f.upload());
        var gc = new GarbageCollector(db, storage, tx, false);
        gc.runOne();
        gc.runOne();
        assertNotNull(db.one("select erased_at from uploads where id=?", f.upload()).get("erased_at"));
        assertEquals(
                0L,
                db.sql.queryForObject("select reserved from accounts where id=?", Long.class, f.owner()));
    }

    @Test
    void cannotSignPartsBeyondReservedBytes() throws Exception {
        var f = uploaded();
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> uploads(storage).parts(jwt(f.owner()), f.upload(), new Uploads.Parts(2, 1)));
    }

    ScratchFixture scratchFixture() throws Exception {
        var f = queued();
        UUID job = (UUID) db.one("select id from jobs where video_id=?", f.video()).get("id"),
                attempt = UUID.randomUUID();
        db.sql.update(
                "insert into processing_attempts(id,job_id,started_at) values(?,?,now()-interval '2 days')",
                attempt,
                job);
        db.sql.update(
                "update jobs set state='RUNNING',attempt=?,attempts=1,lease_until=now()+interval '90"
                        + " seconds' where id=?",
                attempt,
                job);
        var space = new ScratchSpace(db, tx, scratch.toString(), 60, true, false);
        var lease = space.begin(job, attempt);
        Files.writeString(lease.path().resolve("source"), "sensitive source");
        return new ScratchFixture(f, job, attempt, space, lease);
    }

    void terminal(ScratchFixture f) {
        db.sql.update("update jobs set state='FAILED',lease_until=null where id=?", f.job());
        db.sql.update(
                "update processing_attempts set state='FAILED',finished_at=now()-interval '2 days' where"
                        + " id=?",
                f.attempt());
    }

    @Test
    void scratchCollectorRespectsLiveOsLockEvenAfterLeaseOwnershipChanges() throws Exception {
        var f = scratchFixture();
        terminal(f);
        assertEquals(0, f.space().runOnce());
        assertTrue(Files.exists(f.lease().path().resolve("source")));
        f.lease().close();
        assertEquals(1, f.space().runOnce());
        assertFalse(Files.exists(f.lease().path()));
        assertNotNull(
                db.one("select scratch_purged_at from processing_attempts where id=?", f.attempt())
                        .get("scratch_purged_at"));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> f.space().begin(f.job(), f.attempt()));
    }

    @Test
    void scratchDoesNotPurgeCurrentJobEvenWithExpiredLease() throws Exception {
        var f = scratchFixture();
        f.lease().close();
        db.sql.update("update jobs set lease_until=now()-interval '1 hour' where id=?", f.job());
        assertEquals(0, f.space().runOnce());
        assertTrue(Files.exists(f.space().path(f.attempt())));
    }

    @Test
    void scratchHoldAndRetentionAreEnforced() throws Exception {
        var f = scratchFixture();
        terminal(f);
        f.lease().close();
        db.sql.update("update videos set legal_hold=true where id=?", f.fixture().video());
        assertEquals(0, f.space().runOnce());
        db.sql.update("update videos set legal_hold=false where id=?", f.fixture().video());
        db.sql.update("update processing_attempts set finished_at=now() where id=?", f.attempt());
        assertEquals(0, f.space().runOnce());
        db.sql.update(
                "update processing_attempts set finished_at=now()-interval '2 days' where id=?",
                f.attempt());
        assertEquals(1, f.space().runOnce());
    }

    @Test
    void scratchNeverMarksAnotherVolumesFilesAsPurged() throws Exception {
        var f = scratchFixture();
        terminal(f);
        f.lease().close();
        var other =
                new ScratchSpace(db, tx, scratch.resolve("other-volume").toString(), 60, true, false);
        assertNotEquals(f.space().volume(), other.volume());
        assertEquals(0, other.runOnce());
        assertTrue(Files.exists(f.space().path(f.attempt())));
        assertNull(
                db.one("select scratch_purged_at from processing_attempts where id=?", f.attempt())
                        .get("scratch_purged_at"));
    }

    @Test
    void scratchSymlinksCannotDeleteNeighborOrUnknownDirectories() throws Exception {
        var f = scratchFixture();
        terminal(f);
        f.lease().close();
        Path neighbor = scratch.resolve("outside");
        Files.createDirectories(neighbor);
        Files.writeString(neighbor.resolve("keep"), "keep");
        Files.createSymbolicLink(f.space().path(f.attempt()).resolve("escape"), neighbor);
        UUID unknown = UUID.randomUUID();
        Files.createDirectories(scratch.resolve(unknown.toString()));
        assertEquals(1, f.space().runOnce());
        assertTrue(Files.exists(neighbor.resolve("keep")));
        assertTrue(Files.exists(scratch.resolve(unknown.toString())));
    }

    @Test
    void scratchFilesystemDeletionSurvivesDatabaseRollback() throws Exception {
        var f = scratchFixture();
        terminal(f);
        f.lease().close();
        tx.executeWithoutResult(
                t -> {
                    assertEquals(1, f.space().runOnce());
                    t.setRollbackOnly();
                });
        assertFalse(Files.exists(f.space().path(f.attempt())));
        assertNull(
                db.one("select scratch_purged_at from processing_attempts where id=?", f.attempt())
                        .get("scratch_purged_at"));
        assertEquals(1, f.space().runOnce());
    }

    @Test
    void deletedVideoScratchIsPurgedAtErasureDeadline() throws Exception {
        var f = scratchFixture();
        f.lease().close();
        erasure(storage).request(f.fixture().video(), f.fixture().owner());
        db.sql.update(
                "update processing_attempts set started_at=now(),finished_at=now() where id=?",
                f.attempt());
        assertEquals(0, f.space().runOnce());
        due(f.fixture().video());
        assertEquals(1, f.space().runOnce());
    }

    @Test
    void scratchVolumeIdentitySurvivesWorkerRecreation() throws Exception {
        var f = scratchFixture();
        terminal(f);
        f.lease().close();
        var replacement = new ScratchSpace(db, tx, scratch.toString(), 60, true, false);
        assertEquals(f.space().volume(), replacement.volume());
        assertEquals(1, replacement.runOnce());
    }

    @Test
    void legacyScratchRequiresOptInAndTerminalAttempt() throws Exception {
        var f = scratchFixture();
        f.lease().close();
        db.sql.update("update processing_attempts set scratch_volume=null where id=?", f.attempt());
        var adopting = new ScratchSpace(db, tx, scratch.toString(), 60, true, true);
        assertEquals(0, adopting.runOnce());
        assertNull(
                db.one("select scratch_volume from processing_attempts where id=?", f.attempt())
                        .get("scratch_volume"));
        terminal(f);
        assertEquals(0, f.space().runOnce());
        UUID unknown = UUID.randomUUID();
        Files.createDirectories(scratch.resolve(unknown.toString()));
        assertEquals(1, adopting.runOnce());
        assertFalse(Files.exists(f.lease().path()));
        assertTrue(Files.exists(scratch.resolve(unknown.toString())));
    }

    @Test
    void survivingSubprocessQuarantinesScratchUntilExit() throws Exception {
        var f = scratchFixture();
        terminal(f);
        var process = mock(ProcessHandle.class);
        when(process.isAlive()).thenReturn(true);
        f.space().quarantine(f.lease(), Set.of(process));
        assertEquals(0, f.space().runOnce());
        assertTrue(Files.exists(f.lease().path()));
        when(process.isAlive()).thenReturn(false);
        f.space().runOnce();
        assertFalse(Files.exists(f.lease().path()));
        assertNotNull(
                db.one("select scratch_purged_at from processing_attempts where id=?", f.attempt())
                        .get("scratch_purged_at"));
    }

    @Test
    void overlappingJanitorDoesNotDropJvmPosixLock() throws Exception {
        var f = scratchFixture();
        terminal(f);
        assertEquals(0, f.space().runOnce());
        var process =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        Path.of("target/test-classes").toAbsolutePath().toString(),
                        "com.example.streaming.ScratchLockProbe",
                        scratch.resolve(".locks").resolve(f.attempt() + ".lock").toString())
                        .start();
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, process.exitValue(), "Another JVM must still see the live OS lock");
        } finally {
            process.destroyForcibly();
            f.lease().close();
        }
    }

    record Fixture(UUID video, String owner, UUID upload, String key, String multipart) {
    }

    record ScratchFixture(
            Fixture fixture, UUID job, UUID attempt, ScratchSpace space, ScratchSpace.Lease lease) {
    }
}
