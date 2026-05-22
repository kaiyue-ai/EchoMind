package com.echomind.console.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Minimal HS256 JWT encoder/decoder for EchoMind access tokens. */
public final class JwtTokenCodec {

    private static final String HMAC = "HmacSHA256";
    private static final String ALG = "HS256";
    private static final String TYP = "JWT";
    private static final long DEFAULT_TTL_SECONDS = 7 * 24 * 60 * 60;
    private static final TypeReference<Map<String, Object>> CLAIMS_TYPE = new TypeReference<>() {};

    private final byte[] secret;
    private final long ttlSeconds;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JwtTokenCodec(String secret, String defaultSecret, long ttlSeconds) {
        this(secret, defaultSecret, ttlSeconds, new ObjectMapper(), Clock.systemUTC());
    }

    public JwtTokenCodec(String secret, String defaultSecret, long ttlSeconds, ObjectMapper objectMapper, Clock clock) {
        String effectiveSecret = secret == null || secret.isBlank() ? defaultSecret : secret;
        this.secret = effectiveSecret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String issue(String subject, Map<String, Object> claims) {
        long now = Instant.now(clock).getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", ALG);
        header.put("typ", TYP);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", subject);
        payload.put("iat", now);
        payload.put("exp", now + ttlSeconds);
        if (claims != null) {
            payload.putAll(claims);
        }

        String unsignedToken = base64UrlJson(header) + "." + base64UrlJson(payload);
        return unsignedToken + "." + sign(unsignedToken);
    }

    public Optional<Map<String, Object>> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return Optional.empty();
        }

        String unsignedToken = parts[0] + "." + parts[1];
        if (!constantTimeEquals(sign(unsignedToken), parts[2])) {
            return Optional.empty();
        }

        try {
            Map<String, Object> header = parseJson(parts[0]);
            if (!ALG.equals(asString(header.get("alg"))) || !TYP.equals(asString(header.get("typ")))) {
                return Optional.empty();
            }
            Map<String, Object> payload = parseJson(parts[1]);
            Long expiresAt = asLong(payload.get("exp"));
            if (expiresAt == null || expiresAt <= Instant.now(clock).getEpochSecond()) {
                return Optional.empty();
            }
            if (asString(payload.get("sub")).isBlank()) {
                return Optional.empty();
            }
            return Optional.of(payload);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String base64UrlJson(Map<String, Object> value) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode JWT", e);
        }
    }

    private Map<String, Object> parseJson(String base64Url) throws Exception {
        byte[] decoded = Base64.getUrlDecoder().decode(base64Url);
        return objectMapper.readValue(decoded, CLAIMS_TYPE);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
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
