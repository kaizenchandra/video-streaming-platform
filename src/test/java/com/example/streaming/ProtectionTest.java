package com.example.streaming;

import com.example.streaming.playback.CdnCredentials;
import com.example.streaming.processing.MediaScanner;
import com.example.streaming.upload.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtectionTest {
    @TempDir
    Path temp;

    static byte[] decode(String value) {
        return Base64.getDecoder().decode(value.replace('-', '+').replace('_', '=').replace('~', '/'));
    }

    @Test
    void cloudFrontPolicyAndSignatureVerify() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        Path key = temp.resolve("key.pem");
        Files.writeString(
                key,
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----");
        var cdn =
                new CdnCredentials("https://stream.example.com", "key-id", key.toString(), 300, false);
        UUID video = UUID.randomUUID(), attempt = UUID.randomUUID();
        Instant now = Instant.ofEpochSecond(1000);
        var cookies = cdn.cookies(video, attempt, now);
        var values = new HashMap<String, String>();
        for (String cookie : cookies) {
            String first = cookie.split(";", 2)[0];
            int eq = first.indexOf('=');
            values.put(first.substring(0, eq), first.substring(eq + 1));
            assertTrue(cookie.contains("Secure"));
            assertTrue(cookie.contains("HttpOnly"));
            assertTrue(cookie.contains("/videos/" + video + "/" + attempt + "/"));
        }
        byte[] policy = decode(values.get("CloudFront-Policy"));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(policy);
        assertEquals(1300, json.at("/Statement/0/Condition/DateLessThan/AWS:EpochTime").asLong());
        assertEquals(
                "https://stream.example.com/videos/" + video + "/" + attempt + "/*",
                json.at("/Statement/0/Resource").asText());
        var verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(policy);
        assertTrue(verifier.verify(decode(values.get("CloudFront-Signature"))));
    }

    @Test
    void invalidCdnOriginRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CdnCredentials("http://bad.example.com", "x", "", 300, false));
    }

    @Test
    void awsNativeEndpointCanBeConstructed() {
        try (var storage =
                     new Storage(
                             "", "", "unit-test-media", "fake", "fake-secret", "static", "us-east-1", false, "")) {
            assertTrue(
                    java.net
                            .URI
                            .create(storage.getUrl("videos/test"))
                            .getHost()
                            .matches("unit-test-media\\.s3(?:\\.us-east-1)?\\.amazonaws\\.com"));
        }
    }

    @Test
    void erasureCannotDeleteRoot() {
        try (var storage =
                     new Storage(
                             "http://localhost:1",
                             "http://localhost:1",
                             "test",
                             "fake",
                             "fake-secret",
                             "static",
                             "us-east-1",
                             true,
                             "")) {
            assertThrows(IllegalArgumentException.class, () -> storage.erasePage("videos/"));
        }
    }

    @Test
    void clamavCleanResponse() throws Exception {
        assertEquals("CLEAN", scan("stream: OK\0"));
    }

    @Test
    void clamavInfectedResponse() throws Exception {
        assertThrows(MediaScanner.Infected.class, () -> scan("stream: Eicar-Test-Signature FOUND\0"));
    }

    @Test
    void clamavErrorFailsClosed() throws Exception {
        assertThrows(IOException.class, () -> scan("INSTREAM size limit exceeded. ERROR\0"));
    }

    String scan(String response) throws Exception {
        Path source = temp.resolve(UUID.randomUUID().toString());
        Files.writeString(source, "synthetic input");
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread thread =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (var socket = server.accept()) {
                                            var in = new DataInputStream(socket.getInputStream());
                                            assertArrayEquals(
                                                    "zINSTREAM\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                                                    in.readNBytes(10));
                                            int n;
                                            while ((n = in.readInt()) != 0) in.readNBytes(n);
                                            socket
                                                    .getOutputStream()
                                                    .write(response.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                                        } catch (IOException e) {
                                            throw new UncheckedIOException(e);
                                        }
                                    });
            try {
                return new MediaScanner("clamav", "localhost", server.getLocalPort(), 104857600, 1000, 0)
                        .scan(source);
            } finally {
                thread.join(2000);
            }
        }
    }

    @Test
    void workerDoesNotRequireCdnSigningKey() throws Exception {
        assertFalse(
                new CdnCredentials("https://stream.example.com", "key", "/not-mounted-in-worker", 300, true)
                        .enabled());
    }

    @Test
    void multipartSignatureBindsBodyLength() {
        try (var storage =
                     new Storage(
                             "http://localhost:1",
                             "http://localhost:1",
                             "test",
                             "fake",
                             "fake-secret",
                             "static",
                             "us-east-1",
                             true,
                             "")) {
            String query =
                    java.net.URLDecoder.decode(
                            java.net
                                    .URI
                                    .create(storage.part("sources/" + UUID.randomUUID(), "upload", 1, 1024))
                                    .getRawQuery(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(query.contains("content-length"), "Presigned upload must bind content length");
        }
    }
}
