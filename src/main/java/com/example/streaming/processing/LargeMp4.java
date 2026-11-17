package com.example.streaming.processing;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Bounded unfragmented, self-contained ISO BMFF/MOV gate for the opt-in window policy.
 */
public final class LargeMp4 {
    private static final Set<String> BRANDS = Set.of("isom", "iso2", "mp41", "mp42", "avc1", "qt  ");
    private static final Set<String> TOP = Set.of("ftyp", "moov", "mdat", "free", "wide");
    private static final Set<String> CONTAINERS =
            Set.of("moov", "trak", "mdia", "minf", "dinf", "stbl", "edts");
    private static final Set<String> CODECS =
            Set.of(
                    "avc1", "avc3", "hvc1", "hev1", "av01", "mp4v", "v210", "apch", "apcn", "apcs", "apco",
                    "ap4h", "ap4x", "mp4a", "lpcm", "sowt", "twos", "in24", "in32", "fl32", "fl64", "alac",
                    "ac-3", "ec-3", "Opus");
    private final long deadline;
    private int boxes, references, descriptions, chunks;
    private long mediaStart, mediaEnd;

    private LargeMp4(long deadline) {
        this.deadline = deadline;
    }

    private static void reject() throws MediaScanner.Rejected {
        throw new MediaScanner.Rejected("UNSUPPORTED_LARGE_CONTAINER");
    }

    private static String type(ByteBuffer b) {
        byte[] t = new byte[4];
        b.get(t);
        return new String(t, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static ByteBuffer read(FileChannel f, long position, int length) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(length);
        while (b.hasRemaining()) {
            int n = f.read(b, position + b.position());
            if (n < 0) throw new EOFException();
        }
        return b.flip();
    }

    public static Layout inspect(Path path, long deadline) throws IOException {
        return new LargeMp4(deadline).inspect(path);
    }

    private void check() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        if (System.nanoTime() > deadline)
            throw new java.net.SocketTimeoutException("Container gate deadline");
        if (++boxes > 100000) reject();
    }

    private Layout inspect(Path path) throws IOException {
        long moov = -1;
        int moovBytes = 0, mdat = 0, ftyp = 0;
        try (var f = FileChannel.open(path, StandardOpenOption.READ)) {
            long bytes = f.size(), position = 0;
            while (position < bytes) {
                check();
                if (bytes - position < 8) reject();
                var h = read(f, position, 8);
                long size = Integer.toUnsignedLong(h.getInt());
                String t = type(h);
                int header = 8;
                if (size == 1) {
                    if (bytes - position < 16) reject();
                    size = read(f, position + 8, 8).getLong();
                    header = 16;
                }
                if (size == 0 && t.equals("mdat")) size = bytes - position;
                if (size < header || size > bytes - position || !TOP.contains(t)) reject();
                switch (t) {
                    case "ftyp" -> {
                        if (position != 0
                                || ++ftyp != 1
                                || size < header + 8
                                || size > 4096
                                || !BRANDS.contains(type(read(f, position + header, 4)))) reject();
                    }
                    case "mdat" -> {
                        if (++mdat != 1 || size == header) reject();
                        mediaStart = position + header;
                        mediaEnd = position + size;
                    }
                    case "moov" -> {
                        if (moov != -1 || size > 33554432) reject();
                        moov = position;
                        moovBytes = (int) size;
                    }
                    default -> {
                    }
                }
                position += size;
            }
            if (ftyp != 1 || mdat != 1 || moov < 0) reject();
            inspectBoxes(read(f, moov, moovBytes), 0);
            if (references == 0 || descriptions == 0 || chunks == 0) reject();
            return new Layout(moov, moovBytes, bytes, mediaStart, mediaEnd);
        } catch (BufferUnderflowException | IndexOutOfBoundsException | IllegalArgumentException e) {
            reject();
            throw new AssertionError();
        }
    }

    private void inspectBoxes(ByteBuffer b, int depth) throws IOException {
        if (depth > 16) reject();
        while (b.hasRemaining()) {
            check();
            if (b.remaining() < 8) reject();
            int start = b.position();
            long size = Integer.toUnsignedLong(b.getInt());
            String t = type(b);
            int header = 8;
            if (size == 1) {
                size = b.getLong();
                header = 16;
            }
            if (size < header || size > b.limit() - start) reject();
            var body = b.slice();
            body.limit((int) size - header);
            b.position(start + (int) size);
            if (Set.of("cmov", "rmra", "uuid", "mvex").contains(t)) reject();
            if (CONTAINERS.contains(t)) inspectBoxes(body, depth + 1);
            else if (t.equals("dref")) {
                if (body.getInt() != 0
                        || body.getInt() != 1
                        || body.remaining() != 12
                        || body.getInt() != 12
                        || !type(body).equals("url ")
                        || body.getInt() != 1) reject();
                references++;
            } else if (t.equals("stsd")) {
                if (body.getInt() != 0) reject();
                long count = Integer.toUnsignedLong(body.getInt());
                if (count < 1 || count > 32) reject();
                for (int i = 0; i < count; i++) {
                    int startEntry = body.position();
                    long n = Integer.toUnsignedLong(body.getInt());
                    String codec = type(body);
                    if (n < 16 || n > body.limit() - startEntry || !CODECS.contains(codec)) reject();
                    body.position(startEntry + 14);
                    if (Short.toUnsignedInt(body.getShort()) != 1) reject();
                    body.position(startEntry + (int) n);
                    descriptions++;
                }
                if (body.hasRemaining()) reject();
            } else if (t.equals("stco") || t.equals("co64")) {
                if (body.getInt() != 0) reject();
                long count = Integer.toUnsignedLong(body.getInt());
                int width = t.equals("stco") ? 4 : 8;
                if (count < 1 || count > 1000000 || count * width != body.remaining()) reject();
                for (int i = 0; i < count; i++) {
                    long offset = width == 4 ? Integer.toUnsignedLong(body.getInt()) : body.getLong();
                    if (offset < mediaStart || offset >= mediaEnd) reject();
                }
                chunks++;
            }
        }
    }

    public record Layout(
            long moovOffset, int moovBytes, long bytes, long mediaOffset, long mediaEnd) {
    }
}
