package com.example.streaming.processing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Benign qualification-only coverage-canary insertions are always restored, including on scanner
 * failure.
 */
public final class LargeScannerQualification {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        long window = 67108864;
        var scanner =
                new MediaScanner(
                        "clamav-windowed-mp4",
                        System.getenv().getOrDefault("CLAMAV_HOST", "clamav"),
                        3310,
                        104857600,
                        600000,
                        604800,
                        window,
                        65536,
                        3600000);
        var layout =
                LargeMp4.inspect(
                        path, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
        byte[] eicar =
                "STREAMWORKS-LARGE-WINDOW-COVERAGE-CANARY-V1-0123456789abcdef0123456789"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var positions = new LinkedHashMap<String, Long>();
        positions.put("window_boundary", window - eicar.length / 2);
        if (layout.mediaEnd() > 3L * 1073741824 + 12413)
            positions.put("beyond_2GiB", 3L * 1073741824 + 12345);
        positions.put("media_tail", layout.mediaEnd() - eicar.length - 16);
        var report = new LinkedHashMap<String, Object>();
        report.put("bytes", Files.size(path));
        report.put(
                "canarySignature",
                "Streamworks.Coverage.Canary (qualification-only any-offset NDB rule; not native EICAR)");
        report.put("startedAt", Instant.now().toString());
        var checks = new LinkedHashMap<String, Object>();
        long start = System.nanoTime();
        for (var entry : positions.entrySet()) {
            if (entry.getValue() < layout.mediaOffset()
                    || entry.getValue() + eicar.length > layout.mediaEnd())
                throw new IllegalArgumentException("Fixture too small for requested detection positions");
            try (var f = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                byte[] saved = new byte[eicar.length];
                read(f, ByteBuffer.wrap(saved), entry.getValue());
                try {
                    write(f, ByteBuffer.wrap(eicar), entry.getValue());
                    f.force(false);
                    try {
                        scanner.scan(path);
                        throw new IllegalStateException("Coverage canary not detected: " + entry.getKey());
                    } catch (MediaScanner.Infected expected) {
                        checks.put(
                                entry.getKey(),
                                Map.of(
                                        "offset",
                                        entry.getValue(),
                                        "result",
                                        "INFECTED",
                                        "coverage",
                                        scanner.lastEvidence()));
                        System.err.println("Qualified detection: " + entry.getKey());
                    }
                } finally {
                    write(f, ByteBuffer.wrap(saved), entry.getValue());
                    f.force(false);
                }
            }
        }
        report.put("engine", scanner.lastEngine());
        report.put("detections", checks);
        report.put("elapsedSeconds", (System.nanoTime() - start) / 1e9);
        report.put("result", "PASS");
        System.out.println(new ObjectMapper().findAndRegisterModules().writeValueAsString(report));
    }

    private static void read(FileChannel f, ByteBuffer b, long offset) throws IOException {
        while (b.hasRemaining()) {
            int n = f.read(b, offset + b.position());
            if (n < 0) throw new EOFException();
        }
    }

    private static void write(FileChannel f, ByteBuffer b, long offset) throws IOException {
        while (b.hasRemaining()) f.write(b, offset + b.position());
    }
}
