package com.example.streaming.processing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Benign synthetic media/EICAR conformance fixtures; never uses production content.
 */
public final class ScannerQualification {
    public static void main(String[] args) throws Exception {
        String host = System.getenv().getOrDefault("CLAMAV_HOST", "clamav");
        int port = Integer.parseInt(System.getenv().getOrDefault("CLAMAV_PORT", "3310"));
        var scanner = new MediaScanner("clamav", host, port, 104857600, 60000, 604800);
        Path temp = Files.createTempDirectory("scanner-qualification-");
        var report = new LinkedHashMap<String, Object>();
        long started = System.nanoTime();
        try {
            long expectedDatabase =
                    Long.parseLong(System.getenv().getOrDefault("CLAMAV_EXPECT_DATABASE", "0"));
            var info = awaitReload(scanner, expectedDatabase);
            MediaScanner.requireFresh(info, Instant.now(), 604800);
            report.put("qualifiedAt", Instant.now().toString());
            report.put("engine", info.version());
            report.put("database", info.database());
            report.put("databaseBuiltAt", info.builtAt().toString());
            Path video = temp.resolve("synthetic.mp4");
            ProcessRunner.run(
                    List.of(
                            "/usr/bin/ffmpeg",
                            "-nostdin",
                            "-v",
                            "error",
                            "-f",
                            "lavfi",
                            "-i",
                            "testsrc2=size=320x240:rate=24",
                            "-t",
                            "3",
                            "-c:v",
                            "libx264",
                            "-threads",
                            "2",
                            video.toString()),
                    Duration.ofSeconds(30));
            if (!"CLEAN".equals(scanner.scan(video)))
                throw new IllegalStateException("Clean fixture rejected");
            report.put("synthetic_video", "CLEAN");
            Path virus = temp.resolve("eicar.txt");
            Files.write(
                    virus,
                    Base64.getDecoder()
                            .decode(
                                    "WDVPIVAlQEFQWzRcUFpYNTQoUF4pN0NDKTd9JEVJQ0FSLVNUQU5EQVJELUFOVElWSVJVUy1URVNULUZJTEUhJEgrSCo="));
            expect(scanner, virus, "INFECTED");
            report.put("eicar", "INFECTED");
            Path zip = temp.resolve("eicar.zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("eicar.txt"));
                Files.copy(virus, out);
                out.closeEntry();
            }
            expect(scanner, zip, "INFECTED");
            report.put("archived_eicar", "INFECTED");
            Path big = temp.resolve("expansion.zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(big))) {
                out.putNextEntry(new ZipEntry("expanded.bin"));
                byte[] zero = new byte[1048576];
                for (int i = 0; i < 101; i++) out.write(zero);
                out.closeEntry();
            }
            expect(scanner, big, "LIMIT_EXCEEDED");
            report.put("archive_expansion_limit", "LIMIT_EXCEEDED (application ZIP preflight)");
            Path limit = temp.resolve("client-limit.bin");
            Files.write(limit, new byte[2048]);
            expect(new MediaScanner("clamav", host, port, 1024, 5000, 604800), limit, "LIMIT_EXCEEDED");
            report.put("client_byte_budget", "LIMIT_EXCEEDED");
            try {
                MediaScanner.requireFresh(info, info.builtAt().plusSeconds(604801), 604800);
                throw new IllegalStateException("Stale database accepted");
            } catch (IOException expected) {
                report.put("stale_database_policy", "REJECTED (clock simulated)");
            }
            report.put("elapsedSeconds", (System.nanoTime() - started) / 1e9);
            report.put("result", "PASS");
            System.out.println(new ObjectMapper().writeValueAsString(report));
        } finally {
            try (var paths = Files.walk(temp)) {
                for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    private static MediaScanner.EngineInfo awaitReload(MediaScanner scanner, long expected)
            throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        IOException failure = null;
        while (System.nanoTime() < deadline) {
            try {
                var info = scanner.inspect();
                if (info.database() >= expected) return info;
            } catch (IOException e) {
                // ConcurrentDatabaseReload=no intentionally blocks requests during reload. Production
                // scanning still fails closed; only this qualification harness waits for daemon readiness.
                failure = e;
            }
            Thread.sleep(250);
        }
        throw new IOException("Daemon has not loaded downloaded signatures", failure);
    }

    private static void expect(MediaScanner scanner, Path file, String result) throws IOException {
        try {
            scanner.scan(file);
        } catch (MediaScanner.Rejected e) {
            if (result.equals(e.result())) return;
            throw e;
        }
        throw new IllegalStateException("Expected scanner rejection: " + result);
    }
}
