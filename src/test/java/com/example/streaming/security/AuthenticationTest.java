package com.example.streaming.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthenticationTest {
    private static final String SECRET = "local-development-secret-at-least-32-bytes-long";

    @Test
    void noImplicitDevelopmentAuthenticationInApi() {
        assertThrows(
                IllegalStateException.class,
                () -> new Security().decoder(SECRET, "", "streaming-api", false, false));
    }

    @Test
    void wrongAudienceRejected() {
        var security = new Security();
        var now = Instant.now();
        String token =
                security
                        .jwtEncoder(SECRET)
                        .encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(),
                                        JwtClaimsSet.builder()
                                                .subject("test")
                                                .issuedAt(now)
                                                .expiresAt(now.plusSeconds(60))
                                                .audience(List.of("wrong-audience"))
                                                .build()))
                        .getTokenValue();
        assertThrows(
                JwtValidationException.class,
                () -> security.decoder(SECRET, "", "streaming-api", true, false).decode(token));
    }
}
