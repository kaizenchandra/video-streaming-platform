package com.example.streaming.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class Encoder {
    private final String ffmpeg, ffprobe;
    private final ObjectMapper json;
    private final long timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public Encoder(
            @Value("${media.ffmpeg}") String f,
            @Value("${media.ffprobe}") String p,
            ObjectMapper j,
            @Value("${media.ffmpeg-timeout-seconds:18000}") long timeout) {
        ffmpeg = f;
        ffprobe = p;
        json = j;
        if (timeout < 1 || timeout > 18000)
            throw new IllegalArgumentException("Invalid encoder timeout");
        this.timeout = timeout;
    }

    public static List<Integer> ladder(int height) {
        return java.util.stream.Stream.of(240, 360, 480, 720, 1080).filter(h -> h <= height).toList();
    }

    public static int bitrate(int h) {
        return switch (h) {
            case 240 -> 400;
            case 360 -> 800;
            case 480 -> 1500;
            case 720 -> 3000;
            default -> 5000;
        };
    }

    public static void validate(Path variant) throws Exception {
        String playlist = Files.readString(variant.resolve("index.m3u8"));
        if (!playlist.contains("#EXT-X-ENDLIST"))
            throw new IllegalStateException("Incomplete playlist");
        int n = 0;
        for (String line : playlist.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).toList()) {
            if (!line.matches("segment_[0-9]+\\.ts")
                    || !Files.isRegularFile(variant.resolve(line))
                    || Files.size(variant.resolve(line)) == 0)
                throw new IllegalStateException("Missing or invalid segment");
            n++;
        }
        if (n == 0) throw new IllegalStateException("Empty playlist");
    }

    private String probe(Path source) throws Exception {
        return ProcessRunner.run(
                List.of(
                        ffprobe,
                        "-max_alloc",
                        "268435456",
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
                        source.toString()),
                Duration.ofSeconds(30));
    }

    public Result encode(Path source, Path out) throws Exception {
        var metadata = json.readTree(probe(source));
        var streams = metadata.path("streams");
        if (!streams.isArray() || streams.size() > 16)
            throw new IllegalArgumentException("Unsupported stream count");
        int height = 0;
        int width = 0;
        boolean audio = false;
        for (var stream : streams) {
            if ("video".equals(stream.path("codec_type").asText()) && height == 0) {
                height = stream.path("height").asInt();
                width = stream.path("width").asInt();
                String[] rate = stream.path("avg_frame_rate").asText("0/0").split("/");
                double fps =
                        rate.length == 2
                                ? Double.parseDouble(rate[0]) / Double.parseDouble(rate[1])
                                : Double.NaN;
                if (!Double.isFinite(fps)
                        || fps <= 0
                        || fps > 120
                        || stream.path("disposition").path("attached_pic").asInt() != 0)
                    throw new IllegalArgumentException("Unsupported frame rate or attached picture");
            }
            if ("audio".equals(stream.path("codec_type").asText())) audio = true;
        }
        double duration = metadata.path("format").path("duration").asDouble();
        if (height < 240
                || height > 4320
                || width < 1
                || width > 7680
                || !Double.isFinite(duration)
                || duration <= 0
                || duration > 14400)
            throw new IllegalArgumentException("Unsupported source dimensions or duration");
        var heights = ladder(height);
        Files.createDirectories(out);
        StringBuilder master = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
        for (int h : heights) {
            Path variant = out.resolve(h + "p");
            Files.createDirectories(variant);
            int rate = bitrate(h);
            var args =
                    new ArrayList<>(
                            List.of(
                                    ffmpeg,
                                    "-max_alloc",
                                    "268435456",
                                    "-filter_threads",
                                    "2",
                                    "-nostdin",
                                    "-v",
                                    "error",
                                    "-threads",
                                    "2",
                                    "-xerror",
                                    "-err_detect",
                                    "explode",
                                    "-protocol_whitelist",
                                    "file",
                                    "-format_whitelist",
                                    "mov,matroska,webm,avi,mpegts,flv,mpegvideo",
                                    "-i",
                                    source.toString(),
                                    "-map",
                                    "0:v:0"));
            if (audio) args.addAll(List.of("-map", "0:a:0", "-c:a", "aac", "-b:a", "96k", "-ac", "2"));
            args.addAll(
                    List.of(
                            "-vf",
                            "scale=-2:" + h,
                            "-c:v",
                            "libx264",
                            "-threads",
                            "2",
                            "-preset",
                            "veryfast",
                            "-pix_fmt",
                            "yuv420p",
                            "-b:v",
                            rate + "k",
                            "-maxrate",
                            rate + "k",
                            "-bufsize",
                            (2 * rate) + "k",
                            "-force_key_frames",
                            "expr:gte(t,n_forced*4)",
                            "-sc_threshold",
                            "0",
                            "-hls_time",
                            "4",
                            "-hls_playlist_type",
                            "vod",
                            "-hls_flags",
                            "independent_segments",
                            "-hls_segment_filename",
                            variant.resolve("segment_%05d.ts").toString(),
                            variant.resolve("index.m3u8").toString()));
            ProcessRunner.run(args, Duration.ofSeconds(timeout));
            validate(variant);
            master
                    .append("#EXT-X-STREAM-INF:BANDWIDTH=")
                    .append((rate + 128) * 1000)
                    .append("\n")
                    .append(h)
                    .append("p/index.m3u8\n");
        }
        ProcessRunner.run(
                List.of(
                        ffmpeg,
                        "-max_alloc",
                        "268435456",
                        "-filter_threads",
                        "2",
                        "-nostdin",
                        "-xerror",
                        "-err_detect",
                        "explode",
                        "-v",
                        "error",
                        "-protocol_whitelist",
                        "file",
                        "-format_whitelist",
                        "mov,matroska,webm,avi,mpegts,flv,mpegvideo",
                        "-i",
                        source.toString(),
                        "-frames:v",
                        "1",
                        "-vf",
                        "scale=320:-2",
                        out.resolve("cover.jpg").toString()),
                Duration.ofMinutes(2));
        Files.writeString(out.resolve("master.m3u8"), master);
        return new Result(duration, heights);
    }

    public record Result(double duration, List<Integer> heights) {
    }
}
