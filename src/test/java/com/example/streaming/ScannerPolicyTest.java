package com.example.streaming;

import com.example.streaming.processing.MediaScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ScannerPolicyTest {
    @TempDir
    Path temp;

    @Test
    void parsesVersionAndRejectsStaleOrFutureDatabase() throws Exception {
        var info = MediaScanner.parseEngine("ClamAV 1.4.6/28183/Sat Nov 14 07:00:00 2026");
        assertEquals(28183, info.database());
        assertDoesNotThrow(() -> MediaScanner.requireFresh(info, info.builtAt().plusSeconds(10), 60));
        assertThrows(
                IOException.class,
                () -> MediaScanner.requireFresh(info, info.builtAt().plusSeconds(61), 60));
        assertThrows(
                IOException.class,
                () -> MediaScanner.requireFresh(info, info.builtAt().minusSeconds(301), 60));
        assertThrows(IOException.class, () -> MediaScanner.parseEngine("nonsense"));
    }

    @Test
    void inputBudgetRejectsBeforeOpeningSocket() throws Exception {
        Path p = temp.resolve("too-large");
        Files.write(p, new byte[2048]);
        var e =
                assertThrows(
                        MediaScanner.Rejected.class,
                        () -> new MediaScanner("clamav", "localhost", 1, 1024, 1000, 0).scan(p));
        assertEquals("LIMIT_EXCEEDED", e.result());
    }

    @Test
    void truncatedCleanReplyFailsClosed() throws Exception {
        assertThrows(EOFException.class, () -> scan("stream: OK", 1000, false));
    }

    @Test
    void engineLimitsAreNotMisclassifiedAsMalware() throws Exception {
        var e =
                assertThrows(
                        MediaScanner.Rejected.class,
                        () -> scan("stream: Heuristics.Limits.Exceeded.FileSize FOUND\0", 1000, false));
        assertEquals("LIMIT_EXCEEDED", e.result());
    }

    @Test
    void silentScannerDeadlineIsBounded() throws Exception {
        long start = System.nanoTime();
        assertThrows(SocketTimeoutException.class, () -> scan("", 100, true));
        assertTrue(System.nanoTime() - start < 2_000_000_000L);
    }

    String scan(String reply, long timeout, boolean hang) throws Exception {
        Path p = temp.resolve("fixture");
        Files.writeString(p, "small synthetic input");
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread peer =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (var socket = server.accept()) {
                                            var in = new DataInputStream(socket.getInputStream());
                                            in.readNBytes(10);
                                            int n;
                                            while ((n = in.readInt()) != 0) in.readNBytes(n);
                                            if (hang) Thread.sleep(5000);
                                            else
                                                socket
                                                        .getOutputStream()
                                                        .write(reply.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                                        } catch (IOException | InterruptedException ignored) {
                                        }
                                    });
            try {
                return new MediaScanner("clamav", "localhost", server.getLocalPort(), 104857600, timeout, 0)
                        .scan(p);
            } finally {
                peer.interrupt();
                peer.join(2000);
            }
        }
    }

    @Test
    void compressedEntryCannotBypassScanCoverage() throws Exception {
        Path zip = temp.resolve("large.zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("large"));
            out.write(new byte[8192]);
            out.closeEntry();
        }
        var error =
                assertThrows(
                        MediaScanner.Rejected.class,
                        () -> new MediaScanner("clamav", "localhost", 1, 1024, 1000, 0).scan(zip));
        assertEquals("LIMIT_EXCEEDED", error.result());
    }

    @Test
    void trailingBytesAfterNestedArchiveAreAlsoBudgeted() throws Exception {
        var inner = new ByteArrayOutputStream();
        try (var out = new java.util.zip.ZipOutputStream(inner)) {
            out.putNextEntry(new java.util.zip.ZipEntry("small"));
            out.write(new byte[16]);
            out.closeEntry();
        }
        Path zip = temp.resolve("nested.zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("nested"));
            out.write(inner.toByteArray());
            out.write(new byte[8192]);
            out.closeEntry();
        }
        var error =
                assertThrows(
                        MediaScanner.Rejected.class,
                        () -> new MediaScanner("clamav", "localhost", 1, 1024, 1000, 0).scan(zip));
        assertEquals("LIMIT_EXCEEDED", error.result());
    }
}
