package com.echomind.console.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/** HMAC 签名登录 token。 */
@Component
public class AuthTokenService {

    private static final String HMAC = "HmacSHA256";
    private static final long DEFAULT_TTL_SECONDS = 7 * 24 * 60 * 60;
    private static final String TOKEN_VERSION = "v2";

    private final byte[] secret;
    private final long ttlSeconds;

    public AuthTokenService(
        @Value("${echomind.auth.token-secret:${ECHOMIND_AUTH_TOKEN_SECRET:echomind-dev-secret}}") String secret,
        @Value("${echomind.auth.token-ttl-seconds:${ECHOMIND_AUTH_TOKEN_TTL_SECONDS:604800}}") long ttlSeconds
    ) {
        this.secret = (secret == null || secret.isBlank() ? "echomind-dev-secret" : secret)
            .getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
    }

    public String issue(AuthUser user) {
        long expiresAt = Instant.now().getEpochSecond() + ttlSeconds;
        String payload = TOKEN_VERSION + "." + base64Url(user.userId()) + "." + base64Url(user.username()) + "." + expiresAt;
        return payload + "." + sign(payload);
    }

    public Optional<AuthUser> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 5 || !TOKEN_VERSION.equals(parts[0])) {
            return Optional.empty();
        }
        String payload = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
        if (!constantTimeEquals(sign(payload), parts[4])) {
            return Optional.empty();
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (expiresAt < Instant.now().getEpochSecond()) {
            return Optional.empty();
        }
        return Optional.of(new AuthUser(fromBase64Url(parts[1]), fromBase64Url(parts[2]), true));
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign token", e);
        }
    }

    private String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private String fromBase64Url(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            byte av = i < a.length ? a[i] : 0;
            byte bv = i < b.length ? b[i] : 0;
            diff |= av ^ bv;
        }
        return diff == 0;
    }
}
