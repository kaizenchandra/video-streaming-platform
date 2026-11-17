package com.example.streaming.processing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded synthetic negative corpus; no exploit binaries, production content or outside requests.
 */
public final class AdversarialMediaQualification {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static void ffmpeg(String... args) throws Exception {
        var command =
                new ArrayList<>(
                        List.of("/usr/bin/ffmpeg", "-nostdin", "-v", "error", "-filter_threads", "2"));
        command.addAll(List.of(args));
        ProcessRunner.run(command, Duration.ofSeconds(30));
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("adversarial-media-");
        var report = new LinkedHashMap<String, Object>();
        var encoder = new Encoder("/usr/bin/ffmpeg", "/usr/bin/ffprobe", JSON, 30);
        try {
            Path valid = temp.resolve("valid.mp4");
            ffmpeg(
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc2=size=640x360:rate=24",
                    "-t",
                    "3",
                    "-c:v",
                    "libx264",
                    "-threads",
                    "2",
                    "-pix_fmt",
                    "yuv420p",
                    valid.toString());
            var result = encoder.encode(valid, temp.resolve("valid-out"));
            if (result.heights().size() != 2) throw new IllegalStateException("Valid control failed");
            report.put("valid_control", "two HLS renditions");
            var inputs = new LinkedHashMap<String, Path>();
            Path truncated = temp.resolve("truncated.mp4");
            Files.write(truncated, Arrays.copyOf(Files.readAllBytes(valid), 32));
            inputs.put("truncated_mp4", truncated);
            Path audio = temp.resolve("audio.m4a");
            ffmpeg("-f", "lavfi", "-i", "sine=frequency=440", "-t", "1", "-c:a", "aac", audio.toString());
            inputs.put("audio_without_video", audio);
            Path wide = temp.resolve("wide.mp4");
            ffmpeg(
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc2=size=8000x240:rate=24",
                    "-frames:v",
                    "1",
                    "-c:v",
                    "libx264",
                    "-threads",
                    "2",
                    wide.toString());
            inputs.put("oversized_dimensions", wide);
            Path rate = temp.resolve("rate.mp4");
            ffmpeg(
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc2=size=640x360:rate=240",
                    "-frames:v",
                    "2",
                    "-c:v",
                    "libx264",
                    "-threads",
                    "2",
                    rate.toString());
            inputs.put("excessive_frame_rate", rate);
            Path streams = temp.resolve("streams.mp4");
            var multi = new ArrayList<String>(List.of("-i", valid.toString()));
            for (int i = 0; i < 17; i++) multi.addAll(List.of("-map", "0:v:0"));
            multi.addAll(List.of("-c", "copy", streams.toString()));
            ffmpeg(multi.toArray(String[]::new));
            inputs.put("excessive_stream_count", streams);
            Path corrupt = temp.resolve("corrupt.mp4");
            Files.copy(valid, corrupt);
            String packet =
                    ProcessRunner.run(
                            List.of(
                                    "/usr/bin/ffprobe",
                                    "-v",
                                    "error",
                                    "-select_streams",
                                    "v:0",
                                    "-show_packets",
                                    "-read_intervals",
                                    "%+#48",
                                    "-show_entries",
                                    "packet=pos",
                                    "-of",
                                    "json",
                                    corrupt.toString()),
                            Duration.ofSeconds(10));
            long offset = JSON.readTree(packet).path("packets").get(47).path("pos").asLong();
            try (var f = FileChannel.open(corrupt, StandardOpenOption.WRITE)) {
                f.write(ByteBuffer.wrap(new byte[]{127, -1, -1, -1}), offset);
            }
            // Prove that this case reaches decoding, rather than failing only metadata parsing.
            String corruptMetadata =
                    ProcessRunner.run(
                            List.of(
                                    "/usr/bin/ffprobe",
                                    "-v",
                                    "error",
                                    "-protocol_whitelist",
                                    "file",
                                    "-format_whitelist",
                                    "mov,matroska,webm,avi,mpegts,flv,mpegvideo",
                                    "-show_streams",
                                    "-show_format",
                                    "-of",
                                    "json",
                                    corrupt.toString()),
                            Duration.ofSeconds(30));
            if (JSON.readTree(corruptMetadata).path("streams").isMissingNode())
                throw new AssertionError("Missing corrupt-control metadata");
            report.put(
                    "corrupt_packet_metadata_probe", "VALID JSON; subsequent strict decoding must fail");
            inputs.put("corrupt_nal_length", corrupt);
            try (var canary = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
                canary.setSoTimeout(100);
                AtomicInteger connections = new AtomicInteger();
                Thread receiver =
                        Thread.ofVirtual()
                                .start(
                                        () -> {
                                            while (!Thread.currentThread().isInterrupted())
                                                try (var s = canary.accept()) {
                                                    connections.incrementAndGet();
                                                } catch (java.io.IOException ignored) {
                                                }
                                        });
                try {
                    Path hls = temp.resolve("remote.m3u8");
                    Files.writeString(
                            hls,
                            "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nhttp://127.0.0.1:"
                                    + canary.getLocalPort()
                                    + "/secret.ts\n#EXT-X-ENDLIST\n");
                    inputs.put("remote_hls_reference", hls);
                    Path concat = temp.resolve("local.ffconcat");
                    Files.writeString(concat, "ffconcat version 1.0\nfile '/etc/passwd'\n");
                    inputs.put("local_concat_reference", concat);
                    for (var entry : inputs.entrySet()) {
                        Path out = temp.resolve("rejected-" + entry.getKey());
                        try {
                            encoder.encode(entry.getValue(), out);
                            throw new AssertionError("Adversarial input accepted: " + entry.getKey());
                        } catch (Exception expected) {
                            if (Files.exists(out.resolve("master.m3u8")))
                                throw new AssertionError("Partial master published");
                            report.put(entry.getKey(), "REJECTED: " + expected.getClass().getSimpleName());
                        }
                    }
                    if (connections.get() != 0) throw new AssertionError("Decoder contacted reference URL");
                    report.put("network_canary_connections", connections.get());
                } finally {
                    receiver.interrupt();
                    receiver.join(2000);
                }
            }
            report.put("result", "PASS");
            System.out.println(JSON.writeValueAsString(report));
        } finally {
            try (var paths = Files.walk(temp)) {
                for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
