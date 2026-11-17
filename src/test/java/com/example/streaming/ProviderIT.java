package com.example.streaming;

import com.example.streaming.upload.Storage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.core.sync.RequestBody;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in, writes only a unique synthetic source key. Requires an explicitly selected staging
 * bucket.
 */
@Tag("provider")
@EnabledIfEnvironmentVariable(named = "PROVIDER_CERTIFY", matches = "true")
class ProviderIT {
    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    @Test
    void certifyPrivateVersionedSourceRoundTrip() throws Exception {
        String bucket = required("S3_BUCKET"),
                owner = required("S3_EXPECTED_OWNER"),
                region = required("AWS_REGION");
        assertTrue(owner.matches("[0-9]{12}"));
        try (var store =
                     new Storage("", "", bucket, "unused", "unused", "default", region, false, "")) {
            store.client.headBucket(b -> b.bucket(bucket).expectedBucketOwner(owner));
            var block =
                    store.client.getPublicAccessBlock(b -> b.bucket(bucket)).publicAccessBlockConfiguration();
            assertTrue(
                    block.blockPublicAcls()
                            && block.blockPublicPolicy()
                            && block.ignorePublicAcls()
                            && block.restrictPublicBuckets());
            assertFalse(
                    store.client.getBucketPolicyStatus(b -> b.bucket(bucket)).policyStatus().isPublic());
            assertEquals(
                    "Enabled", store.client.getBucketVersioning(b -> b.bucket(bucket)).statusAsString());
            assertFalse(
                    store
                            .client
                            .getBucketEncryption(b -> b.bucket(bucket))
                            .serverSideEncryptionConfiguration()
                            .rules()
                            .isEmpty());
            String key = "sources/" + UUID.randomUUID();
            String upload = null;
            try {
                byte[] bytes =
                        "legally usable synthetic provider certification fixture"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                upload = store.initiate(key);
                var http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
                URI part = URI.create(store.part(key, upload, 1, bytes.length));
                int wrong =
                        http.send(
                                        HttpRequest.newBuilder(part)
                                                .timeout(java.time.Duration.ofSeconds(30))
                                                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1}))
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode();
                assertTrue(wrong == 400 || wrong == 403, "Wrong-length part accepted");
                assertEquals(
                        200,
                        http.send(
                                        HttpRequest.newBuilder(part)
                                                .timeout(java.time.Duration.ofSeconds(30))
                                                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                store.complete(key, upload, bytes.length);
                store.complete(key, upload, bytes.length);
                String signed = store.getUrl(key);
                var valid =
                        http.send(
                                HttpRequest.newBuilder(URI.create(signed))
                                        .timeout(java.time.Duration.ofSeconds(30))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, valid.statusCode());
                assertArrayEquals(bytes, valid.body());
                URI raw = URI.create(signed);
                URI anonymous = new URI(raw.getScheme(), raw.getAuthority(), raw.getPath(), null, null);
                assertEquals(
                        403,
                        http.send(
                                        HttpRequest.newBuilder(anonymous)
                                                .timeout(java.time.Duration.ofSeconds(30))
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                String tampered = signed.replaceFirst("X-Amz-Signature=[0-9a-f]", "X-Amz-Signature=z");
                assertEquals(
                        403,
                        http.send(
                                        HttpRequest.newBuilder(URI.create(tampered))
                                                .timeout(java.time.Duration.ofSeconds(30))
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                store.client.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromBytes(bytes));
                store.client.deleteObject(b -> b.bucket(bucket).key(key));
                assertFalse(store.erasePage(key));
                assertTrue(store.erasePage(key));
                Files.createDirectories(Path.of("target"));
                Files.writeString(
                        Path.of("target/provider-certification.json"),
                        "{\"result\":\"PASS\",\"scope\":\"AWS private versioned staging S3 multipart,"
                                + " anonymous/tampered denial and permanent version deletion\",\"bucket\":\""
                                + bucket
                                + "\",\"region\":\""
                                + region
                                + "\",\"time\":\""
                                + java.time.Instant.now()
                                + "\"}\n");
            } finally {
                if (upload != null) store.abort(key, upload);
                for (int i = 0; i < 5 && !store.erasePage(key); i++) {
                }
            }
        }
    }
}
