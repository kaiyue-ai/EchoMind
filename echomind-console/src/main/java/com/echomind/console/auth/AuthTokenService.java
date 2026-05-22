package com.echomind.console.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** 客户端用户 JWT access token。 */
@Component
public class AuthTokenService {

    private static final String TOKEN_TYPE = "user";
    private static final String DEFAULT_SECRET = "echomind-dev-secret";

    private final JwtTokenCodec jwt;

    public AuthTokenService(
        @Value("${echomind.auth.token-secret:${ECHOMIND_AUTH_TOKEN_SECRET:echomind-dev-secret}}") String secret,
        @Value("${echomind.auth.token-ttl-seconds:${ECHOMIND_AUTH_TOKEN_TTL_SECONDS:604800}}") long ttlSeconds
    ) {
        this.jwt = new JwtTokenCodec(secret, DEFAULT_SECRET, ttlSeconds);
    }

    public String issue(AuthUser user) {
        return jwt.issue(user.userId(), Map.of(
            "typ", TOKEN_TYPE,
            "userId", user.userId(),
            "username", user.username()
        ));
    }

    public Optional<AuthUser> verify(String token) {
        return jwt.verify(token)
            .filter(claims -> TOKEN_TYPE.equals(stringClaim(claims, "typ")))
            .map(claims -> new AuthUser(
                stringClaim(claims, "userId"),
                stringClaim(claims, "username"),
                true
            ))
            .filter(user -> !user.userId().isBlank() && !user.username().isBlank());
    }

    private String stringClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        return value == null ? "" : String.valueOf(value);
    }
}
