package com.example.streaming.processing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ClamAV INSTREAM with bounded bytes/deadline, fresh database preflight and complete response
 * framing.
 */
@Component
public class MediaScanner {
    private final String mode, host;
    private final int port;
    private final long maximum, timeout, signatureAge, window, overlap, sourceTimeout;
    private final ThreadLocal<Evidence> evidence = new ThreadLocal<>();
    private final ThreadLocal<EngineInfo> last = new ThreadLocal<>();

    public MediaScanner(String mode, String host, int port) {
        this(mode, host, port, 104857600, 600000, 604800);
    }

    public MediaScanner(
            String mode, String host, int port, long maximum, long timeout, long signatureAge) {
        this(mode, host, port, maximum, timeout, signatureAge, 67108864, 65536, 3600000);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MediaScanner(
            @Value("${media.scan.mode:clamav}") String mode,
            @Value("${media.scan.host:clamav}") String host,
            @Value("${media.scan.port:3310}") int port,
            @Value("${media.scan.max-bytes:104857600}") long maximum,
            @Value("${media.scan.timeout-ms:600000}") long timeout,
            @Value("${media.scan.max-signature-age-seconds:604800}") long signatureAge,
            @Value("${media.scan.window-bytes:67108864}") long window,
            @Value("${media.scan.overlap-bytes:65536}") long overlap,
            @Value("${media.scan.source-timeout-ms:3600000}") long sourceTimeout) {
        if (!Set.of("clamav", "clamav-windowed-mp4", "disabled").contains(mode)
                || maximum < 1
                || maximum > 2147483647L
                || timeout < 1
                || timeout > 600000
                || signatureAge < 0
                || sourceTimeout < 1000
                || sourceTimeout > 7200000
                || ("clamav-windowed-mp4".equals(mode)
                && (window < 4096
                || window > 67108864
                || window > maximum
                || overlap < 68
                || overlap > 1048576
                || overlap >= window)))
            throw new IllegalArgumentException("Invalid scanner policy");
        this.mode = mode;
        this.host = host;
        this.port = port;
        this.maximum = maximum;
        this.timeout = timeout;
        this.signatureAge = signatureAge;
        this.window = window;
        this.overlap = overlap;
        this.sourceTimeout = sourceTimeout;
    }

    private static String response(Socket socket) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int c; (c = socket.getInputStream().read()) != -1; ) {
            if (c == 0) return bytes.toString(StandardCharsets.UTF_8);
            if (bytes.size() >= 4096) throw new IOException("Scanner response too large");
            bytes.write(c);
        }
        throw new EOFException("Truncated scanner response");
    }

    public static EngineInfo parseEngine(String text) throws IOException {
        try {
            String[] fields = text.split("/", 3);
            if (fields.length != 3 || !fields[0].startsWith("ClamAV "))
                throw new IllegalArgumentException();
            return new EngineInfo(
                    fields[0],
                    Long.parseLong(fields[1]),
                    LocalDateTime.parse(
                                    fields[2].trim().replaceAll("\\s+", " "),
                                    DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH))
                            .toInstant(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            throw new IOException("Invalid scanner version response", e);
        }
    }

    public static void requireFresh(EngineInfo info, Instant now, long age) throws IOException {
        if (info.builtAt().isAfter(now.plusSeconds(300))
                || info.builtAt().isBefore(now.minusSeconds(age)))
            throw new IOException("Scanner signature database is stale or clock is inconsistent");
    }

    private static boolean isZip(byte[] header) {
        return header.length == 4
                && header[0] == 'P'
                && header[1] == 'K'
                && header[2] == 3
                && header[3] == 4;
    }

    public Evidence lastEvidence() {
        return evidence.get();
    }

    public boolean windowed() {
        return "clamav-windowed-mp4".equals(mode);
    }

    public boolean accepted(String result) {
        return Set.of("CLEAN", "DEV_BYPASS").contains(result)
                || (windowed() && "WINDOWED_MP4_V1".equals(result));
    }

    public long maxBytes() {
        return "disabled".equals(mode) || windowed() ? 21474836480L : maximum;
    }

    public EngineInfo lastEngine() {
        return last.get();
    }

    public void clear() {
        last.remove();
        evidence.remove();
    }

    private Socket connect() throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), (int) Math.min(3000, timeout));
            s.setSoTimeout((int) Math.min(30000, timeout));
            return s;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    public EngineInfo inspect() throws IOException {
        try (var socket = connect()) {
            socket.setSoTimeout((int) Math.min(3000, timeout));
            socket.getOutputStream().write("zVERSION\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return parseEngine(response(socket));
        }
    }

    private void archivePreflight(Path file) throws IOException {
        try (var input = new java.io.PushbackInputStream(Files.newInputStream(file), 4)) {
            byte[] header = input.readNBytes(4);
            input.unread(header);
            if (isZip(header)) {
                try {
                    inspectZip(input, new ZipBudget(), 0);
                } catch (java.util.zip.ZipException e) {
                    throw new Rejected("UNSCANNABLE_ARCHIVE");
                }
            }
        }
    }

    private void inspectZip(InputStream input, ZipBudget budget, int depth) throws IOException {
        if (depth > 4) throw new Rejected("LIMIT_EXCEEDED");
        try (var zip =
                     new java.util.zip.ZipInputStream(
                             new FilterInputStream(input) {
                                 @Override
                                 public void close() {
                                 }
                             })) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                budget.check(0);
                if (++budget.entries > 1000 || entry.getSize() > maximum)
                    throw new Rejected("LIMIT_EXCEEDED");
                InputStream counted =
                        new InputStream() {
                            long size;

                            public int read() throws IOException {
                                byte[] b = new byte[1];
                                return read(b, 0, 1) == -1 ? -1 : b[0] & 255;
                            }

                            public int read(byte[] b, int off, int len) throws IOException {
                                int n = zip.read(b, off, len);
                                if (n > 0) {
                                    size += n;
                                    budget.check(n);
                                    if (size > maximum) throw new Rejected("LIMIT_EXCEEDED");
                                }
                                return n;
                            }
                        };
                var nested = new java.io.PushbackInputStream(counted, 4);
                byte[] header = nested.readNBytes(4);
                nested.unread(header);
                if (isZip(header)) inspectZip(nested, budget, depth + 1);
                byte[] bytes = new byte[65536];
                while (nested.read(bytes) != -1) {
                }
                zip.closeEntry();
            }
        }
    }

    public String scan(Path path) throws IOException {
        clear();
        if ("disabled".equals(mode)) return "DEV_BYPASS";
        var before =
                Files.readAttributes(
                        path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long size = before.size();
        if (!before.isRegularFile() || size < 1 || size > maxBytes())
            throw new Rejected("LIMIT_EXCEEDED");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sourceTimeout);
        EngineInfo engine = null;
        if (signatureAge > 0) {
            engine = inspect();
            last.set(engine);
            requireFresh(engine, Instant.now(), signatureAge);
        }
        boolean large = size > maximum;
        var layout = large ? LargeMp4.inspect(path, deadline) : null;
        if (!large) archivePreflight(path);
        long covered = 0;
        int windows = 0;
        long[] sent = {0};
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        String policy = large ? "clamav-windowed-mp4-v1" : "clamav-single-v1";
        evidence.set(
                new Evidence(
                        policy, size, 0, 0, 0, large ? window : maximum, large ? overlap : 0, null, false));
        try (var file = java.nio.channels.FileChannel.open(path, StandardOpenOption.READ)) {
            // Scan bounded container metadata as a complete unit, as well as all raw byte windows.
            if (large) scanRange(file, layout.moovOffset(), layout.moovBytes(), deadline, null, 0, sent);
            for (long offset = 0; offset < size; ) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                if (System.nanoTime() > deadline)
                    throw new SocketTimeoutException("Source scan deadline exceeded");
                if (engine != null) {
                    var current = inspect();
                    requireFresh(current, Instant.now(), signatureAge);
                    if (!engine.equals(current))
                        throw new IOException("Signature database changed during scan");
                }
                long length = Math.min(large ? window : maximum, size - offset);
                scanRange(file, offset, length, deadline, digest, covered, sent);
                covered = offset + length;
                windows++;
                evidence.set(
                        new Evidence(
                                policy,
                                size,
                                covered,
                                sent[0],
                                windows,
                                large ? window : maximum,
                                large ? overlap : 0,
                                null,
                                false));
                if (covered == size) break;
                offset = covered - overlap;
            }
            var after =
                    Files.readAttributes(
                            path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (after.size() != size
                    || !Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime()))
                throw new IOException("Source changed during scan");
            evidence.set(
                    new Evidence(
                            policy,
                            size,
                            covered,
                            sent[0],
                            windows,
                            large ? window : maximum,
                            large ? overlap : 0,
                            HexFormat.of().formatHex(digest.digest()),
                            true));
            return large ? "WINDOWED_MP4_V1" : "CLEAN";
        } catch (IOException e) {
            evidence.set(
                    new Evidence(
                            policy,
                            size,
                            covered,
                            sent[0],
                            windows,
                            large ? window : maximum,
                            large ? overlap : 0,
                            null,
                            false));
            throw e;
        }
    }

    private void scanRange(
            java.nio.channels.FileChannel file,
            long offset,
            long length,
            long deadline,
            java.security.MessageDigest digest,
            long alreadyHashed,
            long[] sent)
            throws IOException {
        if (length > maximum) throw new Rejected("LIMIT_EXCEEDED");
        long remainingMs =
                Math.min(timeout, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        if (remainingMs < 1) throw new SocketTimeoutException("Source scan deadline exceeded");
        AtomicBoolean expired = new AtomicBoolean();
        Thread owner = Thread.currentThread();
        try (Socket socket = connect();
             var timer = new ScheduledThreadPoolExecutor(1)) {
            timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            timer.schedule(
                    () -> {
                        expired.set(true);
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                        }
                    },
                    remainingMs,
                    TimeUnit.MILLISECONDS);
            timer.scheduleAtFixedRate(
                    () -> {
                        if (owner.isInterrupted())
                            try {
                                socket.close();
                            } catch (IOException ignored) {
                            }
                    },
                    100,
                    100,
                    TimeUnit.MILLISECONDS);
            try {
                var out = new DataOutputStream(socket.getOutputStream());
                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                byte[] buffer = new byte[65536];
                long position = offset, end = offset + length;
                while (position < end) {
                    if (owner.isInterrupted()) throw new InterruptedIOException();
                    int n =
                            file.read(
                                    java.nio.ByteBuffer.wrap(
                                            buffer, 0, (int) Math.min(buffer.length, end - position)),
                                    position);
                    if (n < 0) throw new EOFException("Source truncated during scan");
                    if (n == 0) continue;
                    out.writeInt(n);
                    out.write(buffer, 0, n);
                    sent[0] += n;
                    if (digest != null) {
                        int skip = (int) Math.min(n, Math.max(0, alreadyHashed - position));
                        digest.update(buffer, skip, n - skip);
                    }
                    position += n;
                }
                out.writeInt(0);
                out.flush();
                String result = response(socket);
                if (result.contains("Heuristics.Limits.Exceeded")
                        || result.toLowerCase(Locale.ROOT).contains("size limit exceeded"))
                    throw new Rejected("LIMIT_EXCEEDED");
                if (result.endsWith(" FOUND")) throw new Infected();
                if (!result.equals("stream: OK")) throw new IOException("Scanner failed closed");
            } catch (IOException e) {
                if (expired.get()) throw new SocketTimeoutException("Scanner overall deadline exceeded");
                throw e;
            }
        }
    }

    public record Evidence(
            String policy,
            long sourceBytes,
            long coveredBytes,
            long transmittedBytes,
            int windows,
            long windowBytes,
            long overlapBytes,
            String sha256,
            boolean complete) {
    }

    public record EngineInfo(String version, long database, Instant builtAt) {
    }

    public static class Rejected extends IOException {
        private final String result;

        public Rejected(String result) {
            super("Scanner rejected source: " + result);
            this.result = result;
        }

        public String result() {
            return result;
        }
    }

    public static class Infected extends Rejected {
        public Infected() {
            super("INFECTED");
        }
    }

    private final class ZipBudget {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        long total;
        int entries;

        void check(long count) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (System.nanoTime() > deadline)
                throw new SocketTimeoutException("Archive preflight deadline exceeded");
            total += count;
            if (total > maximum * 4) throw new Rejected("LIMIT_EXCEEDED");
        }
    }
}
