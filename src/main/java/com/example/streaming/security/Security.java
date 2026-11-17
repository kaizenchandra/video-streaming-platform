package com.example.streaming.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Configuration
public class Security {
    @Bean
    JwtEncoder jwtEncoder(@Value("${media.auth-secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8)));
    }

    @Bean
    JwtDecoder decoder(
            @Value("${media.auth-secret}") String secret,
            @Value("${media.issuer}") String issuer,
            @Value("${media.audience:streaming-api}") String audience,
            @Value("${media.dev-auth:false}") boolean development,
            @Value("${media.worker:false}") boolean worker) {
        if (issuer.isBlank() && !development && !worker)
            throw new IllegalStateException(
                    "Configure OIDC_ISSUER or explicitly enable development identity");
        NimbusJwtDecoder decoder =
                issuer.isBlank()
                        ? NimbusJwtDecoder.withSecretKey(
                                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build()
                        : (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
        var standard =
                issuer.isBlank()
                        ? JwtValidators.createDefault()
                        : JwtValidators.createDefaultWithIssuer(issuer);
        org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> audienceValidator =
                jwt ->
                        jwt.getAudience().contains(audience)
                                ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                                : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                                new org.springframework.security.oauth2.core.OAuth2Error(
                                        "invalid_token", "Audience mismatch", null));
        decoder.setJwtValidator(
                new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                        standard, audienceValidator));
        return decoder;
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity h, @Value("${media.worker:false}") boolean worker)
            throws Exception {
        if (worker)
            return h.csrf(c -> c.disable())
                    .authorizeHttpRequests(
                            a ->
                                    a.requestMatchers("/actuator/health/**", "/actuator/prometheus")
                                            .permitAll()
                                            .anyRequest()
                                            .denyAll())
                    .build();
        return h.csrf(c -> c.disable())
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/",
                                                "/index.html",
                                                "/app.js",
                                                "/dev/token",
                                                "/internal/media-auth",
                                                "/actuator/health/**",
                                                "/actuator/prometheus")
                                        .permitAll()
                                        .requestMatchers("/api/**")
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {
                }))
                .build();
    }

    @RestController
    static class DevIdentity {
        private final JwtEncoder encoder;
        private final boolean enabled;

        DevIdentity(JwtEncoder e, @Value("${media.dev-auth}") boolean enabled) {
            encoder = e;
            this.enabled = enabled;
        }

        @PostMapping("/dev/token")
        Map<String, String> token(@RequestParam(defaultValue = "creator") String user) {
            if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if (!user.matches("[a-zA-Z0-9_-]{1,60}"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            var now = Instant.now();
            var claims =
                    JwtClaimsSet.builder()
                            .subject(user)
                            .issuer("local-development")
                            .claim(
                                    "roles",
                                    user.equals("moderator")
                                            ? java.util.List.of("MODERATOR")
                                            : java.util.List.of("CREATOR"))
                            .audience(java.util.List.of("streaming-api"))
                            .issuedAt(now)
                            .expiresAt(now.plusSeconds(3600))
                            .build();
            return Map.of(
                    "token",
                    encoder
                            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                            .getTokenValue());
        }
    }
}
