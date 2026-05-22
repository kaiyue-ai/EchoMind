package com.echomind.console.admin;

import com.echomind.console.auth.JwtTokenCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** 管理端专用 JWT access token，避免复用客户端用户 token。 */
@Component
public class AdminTokenService {

    private static final String TOKEN_TYPE = "admin";
    private static final String DEFAULT_SECRET = "echomind-admin-dev-secret";

    private final JwtTokenCodec jwt;

    public AdminTokenService(
        @Value("${echomind.admin.token-secret:${ECHOMIND_ADMIN_TOKEN_SECRET:echomind-admin-dev-secret}}") String secret,
        @Value("${echomind.admin.token-ttl-seconds:${ECHOMIND_ADMIN_TOKEN_TTL_SECONDS:604800}}") long ttlSeconds
    ) {
        this.jwt = new JwtTokenCodec(secret, DEFAULT_SECRET, ttlSeconds);
    }

    public String issue(AdminUser user) {
        return jwt.issue(user.adminId(), Map.of(
            "typ", TOKEN_TYPE,
            "adminId", user.adminId(),
            "username", user.username()
        ));
    }

    public Optional<AdminUser> verify(String token) {
        return jwt.verify(token)
            .filter(claims -> TOKEN_TYPE.equals(stringClaim(claims, "typ")))
            .map(claims -> new AdminUser(
                stringClaim(claims, "adminId"),
                stringClaim(claims, "username"),
                true
            ))
            .filter(user -> !user.adminId().isBlank() && !user.username().isBlank());
    }

    private String stringClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        return value == null ? "" : String.valueOf(value);
    }
}
