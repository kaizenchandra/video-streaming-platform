package com.example.streaming;

import com.example.streaming.processing.Encoder;
import com.example.streaming.processing.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MediaTest {
    @TempDir
    Path temp;

    @Test
    void neverUpscales() {
        assertEquals(List.of(240, 360, 480), Encoder.ladder(576));
        assertTrue(Encoder.ladder(100).isEmpty());
    }

    @Test
    void rejectsPartialManifest() throws Exception {
        Files.writeString(temp.resolve("index.m3u8"), "#EXTM3U\nsegment_00000.ts\n");
        assertThrows(IllegalStateException.class, () -> Encoder.validate(temp));
    }

    @Test
    void rejectsMissingSegment() throws Exception {
        Files.writeString(temp.resolve("index.m3u8"), "#EXTM3U\nsegment_00000.ts\n#EXT-X-ENDLIST\n");
        assertThrows(IllegalStateException.class, () -> Encoder.validate(temp));
    }

    @Test
    void rejectsTraversal() throws Exception {
        Files.writeString(temp.resolve("index.m3u8"), "#EXTM3U\n../segment_00000.ts\n#EXT-X-ENDLIST\n");
        assertThrows(IllegalStateException.class, () -> Encoder.validate(temp));
    }

    @Test
    void acceptsCompleteManifest() throws Exception {
        Files.writeString(temp.resolve("index.m3u8"), "#EXTM3U\nsegment_00000.ts\n#EXT-X-ENDLIST\n");
        Files.write(temp.resolve("segment_00000.ts"), new byte[]{1});
        assertDoesNotThrow(() -> Encoder.validate(temp));
    }

    @Test
    void processTimeoutKillsChild() {
        assertThrows(
                java.util.concurrent.TimeoutException.class,
                () -> ProcessRunner.run(List.of("/bin/sleep", "5"), Duration.ofMillis(30)));
    }

    @Test
    void processFailure() {
        assertThrows(
                java.io.IOException.class,
                () -> ProcessRunner.run(List.of("/usr/bin/false"), Duration.ofSeconds(1)));
    }
}
