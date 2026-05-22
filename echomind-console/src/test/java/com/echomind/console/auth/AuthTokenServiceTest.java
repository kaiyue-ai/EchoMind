package com.echomind.console.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTokenServiceTest {

    @Test
    void issuesStandardThreePartJwt() {
        AuthTokenService service = new AuthTokenService("test-secret", 3600);

        String token = service.issue(new AuthUser("user-a", "alice", true));

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(json(parts[0])).contains("\"alg\":\"HS256\"").contains("\"typ\":\"JWT\"");
        assertThat(json(parts[1]))
            .contains("\"sub\":\"user-a\"")
            .contains("\"typ\":\"user\"")
            .contains("\"userId\":\"user-a\"")
            .contains("\"username\":\"alice\"");
        assertThat(service.verify(token)).contains(new AuthUser("user-a", "alice", true));
    }

    @Test
    void rejectsExpiredTamperedAndWrongSecretTokens() {
        AuthTokenService service = new AuthTokenService("test-secret", 3600);
        AuthTokenService wrongSecret = new AuthTokenService("other-secret", 3600);
        JwtTokenCodec issuer = new JwtTokenCodec(
            "test-secret",
            "default-secret",
            1,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            Clock.fixed(Instant.parse("2026-05-19T00:00:00Z"), ZoneOffset.UTC)
        );
        JwtTokenCodec verifier = new JwtTokenCodec(
            "test-secret",
            "default-secret",
            1,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            Clock.fixed(Instant.parse("2026-05-19T00:00:02Z"), ZoneOffset.UTC)
        );

        String token = service.issue(new AuthUser("user-a", "alice", true));
        String expiredToken = issuer.issue("user-a", Map.of("typ", "user", "userId", "user-a", "username", "alice"));

        assertThat(wrongSecret.verify(token)).isEmpty();
        assertThat(service.verify(token + "x")).isEmpty();
        assertThat(verifier.verify(expiredToken)).isEmpty();
    }

    private String json(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }
}
