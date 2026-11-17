package com.example.streaming.upload;

import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Standalone local-fixture bootstrap launched with Spring Boot PropertiesLauncher, without the API.
 */
public final class StorageBootstrap {
    public static void main(String[] args) {
        String endpoint = System.getenv().getOrDefault("S3_ENDPOINT", "http://storage:4566");
        try (var storage =
                     new Storage(
                             endpoint,
                             endpoint,
                             "media",
                             "localstream",
                             "localstream-development-only",
                             "static",
                             "us-east-1",
                             true,
                             "")) {
            if (args.length == 3 && "verify-erased".equals(args[0])) {
                String video = java.util.UUID.fromString(args[1]).toString(),
                        upload = java.util.UUID.fromString(args[2]).toString();
                for (String prefix : java.util.List.of("videos/" + video + "/", "sources/" + upload)) {
                    var inventory =
                            storage.client.listObjectVersions(
                                    b -> b.bucket(storage.bucket).prefix(prefix).maxKeys(1));
                    if (!inventory.versions().isEmpty() || !inventory.deleteMarkers().isEmpty())
                        throw new IllegalStateException("Erasure inventory is not empty");
                }
                System.out.println("Local primary-bucket version inventory verified empty");
                return;
            }
            try {
                storage.client.headBucket(b -> b.bucket(storage.bucket));
            } catch (S3Exception e) {
                if (e.statusCode() != 404) throw e;
                storage.client.createBucket(b -> b.bucket(storage.bucket));
            }
            storage.client.putBucketVersioning(
                    b ->
                            b.bucket(storage.bucket)
                                    .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
            // AWS abort-incomplete lifecycle is provisioned by Terraform; this MinIO fixture rejects that
            // action.
            System.out.println("Local versioned media bucket initialized");
        }
    }
}
