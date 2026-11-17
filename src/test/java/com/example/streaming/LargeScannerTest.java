package com.example.streaming;

import com.example.streaming.processing.LargeMp4;
import com.example.streaming.processing.MediaScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LargeScannerTest {
    @TempDir
    Path temp;

    static byte[] box(String type, byte[]... parts) throws Exception {
        var data = new ByteArrayOutputStream();
        for (var p : parts) data.write(p);
        return ByteBuffer.allocate(data.size() + 8)
                .putInt(data.size() + 8)
                .put(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .put(data.toByteArray())
                .array();
    }

    static byte[] ints(int... numbers) {
        var b = ByteBuffer.allocate(numbers.length * 4);
        for (int n : numbers) b.putInt(n);
        return b.array();
    }

    static byte[] source(int body, boolean external) throws Exception {
        byte[] ftyp = box("ftyp", "isom".getBytes(), ints(0));
        byte[] sample =
                ByteBuffer.allocate(16)
                        .putInt(16)
                        .put("avc1".getBytes())
                        .put(new byte[6])
                        .putShort((short) 1)
                        .array();
        byte[] dref = box("dref", ints(0, 1), box("url ", ints(external ? 0 : 1)));
        byte[] stsd = box("stsd", ints(0, 1), sample), stco = box("stco", ints(0, 1, ftyp.length + 8));
        byte[] moov =
                box(
                        "moov",
                        box("trak", box("mdia", box("minf", box("dinf", dref), box("stbl", stsd, stco)))));
        var out = new ByteArrayOutputStream();
        out.write(ftyp);
        out.write(box("mdat", new byte[body]));
        out.write(moov);
        return out.toByteArray();
    }

    Path file(byte[] bytes) throws Exception {
        Path p = temp.resolve(UUID.randomUUID() + ".mp4");
        Files.write(p, bytes);
        return p;
    }

    MediaScanner scanner(int port, long deadline) {
        return new MediaScanner(
                "clamav-windowed-mp4", "localhost", port, 8192, 3000, 0, 4096, 128, deadline);
    }

    @Test
    void everyByteIsCoveredAndHashedWithBoundedOverlappingWindows() throws Exception {
        byte[] bytes = source(16000, false);
        Path p = file(bytes);
        try (var engine = new Engine(0, 0)) {
            var scan = scanner(engine.port(), 10000);
            assertEquals("WINDOWED_MP4_V1", scan.scan(p));
            var e = scan.lastEvidence();
            assertTrue(e.complete());
            assertEquals(bytes.length, e.coveredBytes());
            assertEquals(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), e.sha256());
            assertTrue(engine.streams.stream().allMatch(b -> b.length <= 4096));
            assertEquals(e.windows() + 1, engine.streams.size());
            long offset = 0;
            for (int i = 1; i < engine.streams.size(); i++) {
                byte[] window = engine.streams.get(i);
                assertArrayEquals(
                        Arrays.copyOfRange(bytes, (int) offset, (int) offset + window.length), window);
                offset += window.length - 128;
            }
            assertTrue(e.transmittedBytes() > e.sourceBytes());
        }
    }

    @Test
    void overlapDetectsSignatureSplitAcrossOrdinaryBoundary() throws Exception {
        byte[] bytes = source(16000, false);
        byte[] marker = "BOUNDARY_TEST_SIGNATURE".getBytes();
        System.arraycopy(marker, 0, bytes, 4096 - 10, marker.length);
        try (var engine = new Engine(0, 0)) {
            var scan = scanner(engine.port(), 10000);
            assertThrows(MediaScanner.Infected.class, () -> scan.scan(file(bytes)));
            assertFalse(scan.lastEvidence().complete());
        }
    }

    @Test
    void laterWindowDisconnectCannotProduceCompleteVerdict() throws Exception {
        try (var engine = new Engine(3, 0)) {
            var scan = scanner(engine.port(), 10000);
            Path p = file(source(16000, false));
            assertThrows(IOException.class, () -> scan.scan(p));
            assertFalse(scan.lastEvidence().complete());
            assertEquals(4096, scan.lastEvidence().coveredBytes());
        }
    }

    @Test
    void wholeSourceDeadlineDoesNotResetForEachWindow() throws Exception {
        try (var engine = new Engine(0, 400)) {
            var scan = scanner(engine.port(), 1000);
            Path p = file(source(16000, false));
            long start = System.nanoTime();
            assertThrows(SocketTimeoutException.class, () -> scan.scan(p));
            assertTrue(System.nanoTime() - start < 3_000_000_000L);
            assertFalse(scan.lastEvidence().complete());
        }
    }

    @Test
    void externalMediaReferencesAreRejectedBeforeScannerConnection() throws Exception {
        assertThrows(
                MediaScanner.Rejected.class, () -> scanner(1, 10000).scan(file(source(16000, true))));
    }

    @Test
    void archiveCannotMasqueradeAsLargeMedia() throws Exception {
        assertThrows(MediaScanner.Rejected.class, () -> scanner(1, 10000).scan(file(new byte[16000])));
    }

    @Test
    void truncatedAndOverflowingBoxesFailClosed() throws Exception {
        for (byte[] bytes :
                List.of(
                        new byte[7],
                        ByteBuffer.allocate(16).putInt(1).put("mdat".getBytes()).putLong(-1).array(),
                        box("moov", ints(100)))) {
            assertThrows(
                    MediaScanner.Rejected.class,
                    () -> LargeMp4.inspect(file(bytes), System.nanoTime() + 1_000_000_000L));
        }
    }

    @Test
    void chunkOffsetsCannotEscapeMediaPayload() throws Exception {
        byte[] bytes = source(16000, false);
        ByteBuffer.wrap(bytes, bytes.length - 4, 4).putInt(1);
        assertThrows(
                MediaScanner.Rejected.class,
                () -> LargeMp4.inspect(file(bytes), System.nanoTime() + 1_000_000_000L));
    }

    @Test
    void legacyModeCannotAcceptLargeWindowVerdict() {
        assertFalse(new MediaScanner("clamav", "localhost", 1).accepted("WINDOWED_MP4_V1"));
        assertTrue(scanner(1, 1000).accepted("WINDOWED_MP4_V1"));
        assertEquals(21474836480L, scanner(1, 1000).maxBytes());
    }

    static final class Engine implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        final List<byte[]> streams = Collections.synchronizedList(new ArrayList<>());
        final Thread thread;
        final int failAt;
        final long delay;
        volatile boolean running = true;

        Engine(int failAt, long delay) throws Exception {
            this.failAt = failAt;
            this.delay = delay;
            thread =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        while (running)
                                            try (var s = server.accept()) {
                                                var in = new DataInputStream(s.getInputStream());
                                                in.readNBytes(10);
                                                var bytes = new ByteArrayOutputStream();
                                                int n;
                                                while ((n = in.readInt()) != 0) {
                                                    if (n < 0 || n > 65536) throw new IOException();
                                                    bytes.write(in.readNBytes(n));
                                                }
                                                byte[] data = bytes.toByteArray();
                                                streams.add(data);
                                                if (delay > 0) Thread.sleep(delay);
                                                if (streams.size() == failAt) continue;
                                                String result =
                                                        new String(data, java.nio.charset.StandardCharsets.ISO_8859_1)
                                                                .contains("BOUNDARY_TEST_SIGNATURE")
                                                                ? "stream: test FOUND\0"
                                                                : "stream: OK\0";
                                                s.getOutputStream()
                                                        .write(result.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                                            } catch (Exception ignored) {
                                            }
                                    });
        }

        int port() {
            return server.getLocalPort();
        }

        public void close() throws Exception {
            running = false;
            server.close();
            thread.interrupt();
            thread.join(2000);
        }
    }
}
