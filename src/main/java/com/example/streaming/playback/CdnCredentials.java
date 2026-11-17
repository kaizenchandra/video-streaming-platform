package com.example.streaming.playback;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public class CdnCredentials {
    private final String origin, keyId;
    private final PrivateKey key;
    private final long ttl;

    public CdnCredentials(
            @Value("${media.cdn.origin:}") String origin,
            @Value("${media.cdn.key-id:}") String keyId,
            @Value("${media.cdn.private-key-file:}") String file,
            @Value("${media.cdn.ttl-seconds:300}") long ttl,
            @Value("${media.worker:false}") boolean worker)
            throws Exception {
        this.origin = origin;
        this.keyId = keyId;
        this.ttl = ttl;
        if (ttl < 30 || ttl > 900)
            throw new IllegalArgumentException("CDN TTL must be 30..900 seconds");
        if (origin.isEmpty() || worker) {
            key = null;
            return;
        }
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !uri.getPath().isEmpty()
                || keyId.isBlank())
            throw new IllegalArgumentException("CDN origin must be an HTTPS origin without a path");
        String pem =
                Files.readString(Path.of(file))
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
        key =
                KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        if (((java.security.interfaces.RSAPrivateKey) key).getModulus().bitLength() != 2048)
            throw new IllegalArgumentException("CloudFront key must be RSA 2048");
    }

    public static String encode(byte[] bytes) {
        return Base64.getEncoder()
                .encodeToString(bytes)
                .replace('+', '-')
                .replace('=', '_')
                .replace('/', '~');
    }

    public boolean enabled() {
        return key != null;
    }

    public String path(UUID video, UUID attempt) {
        return "/videos/" + video + "/" + attempt + "/";
    }

    public long ttl() {
        return ttl;
    }

    public List<String> cookies(UUID video, UUID attempt, Instant now)
            throws GeneralSecurityException {
        String prefix = path(video, attempt);
        String policy =
                "{\"Statement\":[{\"Resource\":\""
                        + origin
                        + prefix
                        + "*\",\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":"
                        + now.plusSeconds(ttl).getEpochSecond()
                        + "}}}]}";
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(key);
        signer.update(policy.getBytes(StandardCharsets.UTF_8));
        return List.of(
                cookie("CloudFront-Policy", encode(policy.getBytes(StandardCharsets.UTF_8)), prefix),
                cookie("CloudFront-Signature", encode(signer.sign()), prefix),
                cookie("CloudFront-Key-Pair-Id", keyId, prefix));
    }

    private String cookie(String name, String value, String path) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(path)
                .maxAge(ttl)
                .build()
                .toString();
    }
}
