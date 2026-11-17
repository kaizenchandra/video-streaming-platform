package com.example.streaming;

import com.example.streaming.identity.Identity;
import com.example.streaming.moderation.Moderation;
import com.example.streaming.processing.MediaScanner;
import com.example.streaming.shared.Db;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class WindowPolicyTest {
    Moderation policy(boolean enabled, boolean complete, String scanHash) {
        var db = mock(Db.class);
        String hash = "a".repeat(64);
        when(db.one(anyString(), any()))
                .thenReturn(
                        new HashMap<>(
                                Map.of(
                                        "scan_result",
                                        "WINDOWED_MP4_V1",
                                        "sha256",
                                        hash,
                                        "scan_evidence",
                                        "{\"policy\":\"clamav-windowed-mp4-v1\",\"complete\":"
                                                + complete
                                                + ",\"sourceBytes\":10000,\"coveredBytes\":10000,\"windows\":3,\"sha256\":\""
                                                + scanHash
                                                + "\"}")));
        return new Moderation(
                db,
                mock(Identity.class),
                new MediaScanner(enabled ? "clamav-windowed-mp4" : "clamav", "localhost", 1));
    }

    @Test
    void windowedReviewRequiresExplicitPolicy() {
        assertFalse(policy(false, true, "a".repeat(64)).scanAccepted(UUID.randomUUID()));
    }

    @Test
    void windowedReviewRequiresCompleteCoverage() {
        assertFalse(policy(true, false, "a".repeat(64)).scanAccepted(UUID.randomUUID()));
    }

    @Test
    void windowedReviewRequiresMatchingSourceHash() {
        assertFalse(policy(true, true, "b".repeat(64)).scanAccepted(UUID.randomUUID()));
    }

    @Test
    void completedMatchingCoverageCanBeManuallyReviewed() {
        assertTrue(policy(true, true, "a".repeat(64)).scanAccepted(UUID.randomUUID()));
    }
}
