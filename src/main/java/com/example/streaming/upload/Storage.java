package com.example.streaming.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;

@Component
public class Storage implements AutoCloseable {
    public final S3Client client;
    public final String bucket;
    private final S3Presigner signer;
    private final S3Presigner delivery;
    private final String cacheControl;

    public Storage(
            @Value("${media.endpoint}") String endpoint,
            @Value("${media.public-endpoint}") String publicEndpoint,
            @Value("${media.bucket}") String bucket,
            @Value("${media.access-key}") String access,
            @Value("${media.secret-key}") String secret,
            @Value("${media.credentials-mode:static}") String credentialMode,
            @Value("${media.region:us-east-1}") String region,
            @Value("${media.path-style:true}") boolean pathStyle,
            @Value("${media.cdn.origin:}") String cdnOrigin) {
        this.bucket = bucket;
        cacheControl =
                cdnOrigin.isBlank()
                        ? "private,max-age=31536000,immutable"
                        : "public,max-age=0,s-maxage=31536000,immutable";
        AwsCredentialsProvider credentials =
                "default".equals(credentialMode)
                        ? DefaultCredentialsProvider.create()
                        : StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret));
        var config = S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build();
        var clientBuilder =
                S3Client.builder()
                        .region(Region.of(region))
                        .credentialsProvider(credentials)
                        .serviceConfiguration(config)
                        .overrideConfiguration(
                                ClientOverrideConfiguration.builder()
                                        .apiCallTimeout(Duration.ofMinutes(10))
                                        .apiCallAttemptTimeout(Duration.ofMinutes(5))
                                        .build());
        if (!endpoint.isBlank()) clientBuilder.endpointOverride(URI.create(endpoint));
        client = clientBuilder.build();
        var deliveryBuilder =
                S3Presigner.builder()
                        .region(Region.of(region))
                        .credentialsProvider(credentials)
                        .serviceConfiguration(config);
        if (!endpoint.isBlank()) deliveryBuilder.endpointOverride(URI.create(endpoint));
        delivery = deliveryBuilder.build();
        var signerBuilder =
                S3Presigner.builder()
                        .region(Region.of(region))
                        .credentialsProvider(credentials)
                        .serviceConfiguration(config);
        if (!publicEndpoint.isBlank()) signerBuilder.endpointOverride(URI.create(publicEndpoint));
        signer = signerBuilder.build();
    }

    public String initiate(String key) {
        return client
                .createMultipartUpload(
                        b -> b.bucket(bucket).key(key).contentType("application/octet-stream"))
                .uploadId();
    }

    public String part(String key, String upload, int number, long bytes) {
        return signer
                .presignUploadPart(
                        b ->
                                b.signatureDuration(Duration.ofMinutes(15))
                                        .uploadPartRequest(
                                                p ->
                                                        p.bucket(bucket)
                                                                .key(key)
                                                                .uploadId(upload)
                                                                .partNumber(number)
                                                                .contentLength(bytes)))
                .url()
                .toString();
    }

    public HeadObjectResponse head(String key) {
        return client.headObject(b -> b.bucket(bucket).key(key));
    }

    public void complete(String key, String upload, long expected) {
        try {
            if (head(key).contentLength() == expected) return;
        } catch (NoSuchKeyException e) {
        } catch (S3Exception e) {
            if (e.statusCode() != 404) throw e;
        }
        var parts = new ArrayList<Part>();
        Integer marker = null;
        do {
            final Integer m = marker;
            var response =
                    client.listParts(b -> b.bucket(bucket).key(key).uploadId(upload).partNumberMarker(m));
            parts.addAll(response.parts());
            marker = Boolean.TRUE.equals(response.isTruncated()) ? response.nextPartNumberMarker() : null;
        } while (marker != null);
        long size = parts.stream().mapToLong(Part::size).sum();
        com.example.streaming.shared.Db.require(size == expected, "Unexpected uploaded size");
        for (int i = 0; i < parts.size(); i++) {
            var p = parts.get(i);
            com.example.streaming.shared.Db.require(
                    p.partNumber() == i + 1 && (i == parts.size() - 1 || p.size() >= 5242880),
                    "Invalid multipart layout");
        }
        client.completeMultipartUpload(
                b ->
                        b.bucket(bucket)
                                .key(key)
                                .uploadId(upload)
                                .multipartUpload(
                                        u ->
                                                u.parts(
                                                        parts.stream()
                                                                .map(
                                                                        p ->
                                                                                CompletedPart.builder()
                                                                                        .partNumber(p.partNumber())
                                                                                        .eTag(p.eTag())
                                                                                        .build())
                                                                .toList())));
    }

    public void abort(String key, String upload) {
        try {
            client.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(upload));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) throw e;
        }
    }

    public void download(String key, Path file) {
        client.getObject(b -> b.bucket(bucket).key(key), file);
    }

    public void put(String key, Path file, String type) {
        client.putObject(
                b -> b.bucket(bucket).key(key).contentType(type).cacheControl(cacheControl),
                RequestBody.fromFile(file));
    }

    public String getUrl(String key) {
        return delivery
                .presignGetObject(
                        b ->
                                b.signatureDuration(Duration.ofMinutes(2))
                                        .getObjectRequest(g -> g.bucket(bucket).key(key)))
                .url()
                .toString();
    }

    /**
     * Erase at most 1000 versions/markers. Return true only after a subsequent empty listing.
     */
    public boolean erasePage(String prefix) {
        if (!prefix.matches("(?:videos/[0-9a-f-]{36}/(?:[0-9a-f-]{36}/)?|sources/[0-9a-f-]{36})"))
            throw new IllegalArgumentException("Unsafe erasure prefix");
        var page = client.listObjectVersions(v -> v.bucket(bucket).prefix(prefix).maxKeys(1000));
        var objects = new ArrayList<ObjectIdentifier>();
        page.versions()
                .forEach(
                        v ->
                                objects.add(
                                        ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build()));
        page.deleteMarkers()
                .forEach(
                        v ->
                                objects.add(
                                        ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build()));
        if (objects.isEmpty()) return true;
        var deleted =
                client.deleteObjects(d -> d.bucket(bucket).delete(x -> x.objects(objects).quiet(true)));
        if (!deleted.errors().isEmpty())
            throw S3Exception.builder()
                    .message("Erasure incomplete; provider denied object deletion")
                    .statusCode(503)
                    .build();
        return false;
    }

    public void close() {
        client.close();
        signer.close();
        delivery.close();
    }
}
