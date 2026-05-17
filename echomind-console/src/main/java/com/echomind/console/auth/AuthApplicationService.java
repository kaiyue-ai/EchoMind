package com.echomind.console.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/** 登录用例服务。 */
@Service
@RequiredArgsConstructor
public class AuthApplicationService {

    private final UserAccountRepository repository;
    private final PasswordHasher passwordHasher;
    private final AuthTokenService tokenService;

    @Value("${echomind.auth.default-username:${ECHOMIND_AUTH_DEFAULT_USERNAME:admin}}")
    private String defaultUsername;

    @Value("${echomind.auth.default-password:${ECHOMIND_AUTH_DEFAULT_PASSWORD:admin123}}")
    private String defaultPassword;

    @Transactional
    public AuthResponse login(AuthRequest request) {
        ensureDefaultUser();
        String username = normalizeUsername(request == null ? null : request.username());
        String password = request == null ? null : request.password();
        UserAccountEntity user = repository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (user.getStatus() != UserAccountStatus.ACTIVE || !passwordHasher.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        AuthUser authUser = new AuthUser(user.getUserId(), user.getUsername(), true);
        return new AuthResponse(tokenService.issue(authUser), UserView.from(user));
    }

    @Transactional
    public AuthResponse register(AuthRequest request) {
        String username = normalizeUsername(request == null ? null : request.username());
        String password = request == null ? null : request.password();
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password is required");
        }
        if (repository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("用户名已存在");
        }
        UserAccountEntity entity = new UserAccountEntity();
        entity.setUserId(java.util.UUID.randomUUID().toString());
        entity.setUsername(username);
        entity.setPasswordHash(passwordHasher.hash(password));
        entity.setStatus(UserAccountStatus.ACTIVE);
        repository.save(entity);
        AuthUser authUser = new AuthUser(entity.getUserId(), entity.getUsername(), true);
        return new AuthResponse(tokenService.issue(authUser), UserView.from(entity));
    }

    public UserView current() {
        AuthUser user = AuthContext.current();
        return new UserView(user.userId(), user.username(), user.authenticated());
    }

    @Transactional(readOnly = true)
    public AuthUser requireActiveUser(AuthUser tokenUser) {
        if (tokenUser == null || !tokenUser.authenticated()) {
            return AuthUser.DEFAULT;
        }
        return repository.findByUserIdAndStatus(tokenUser.userId(), UserAccountStatus.ACTIVE)
            .map(user -> new AuthUser(user.getUserId(), user.getUsername(), true))
            .orElse(null);
    }

    @Transactional
    public void ensureDefaultUser() {
        String username = normalizeUsername(defaultUsername);
        UserAccountEntity existing = repository.findByUsername(username).orElse(null);
        if (existing != null) {
            if (AuthUser.DEFAULT_USER_ID.equals(existing.getUserId())) {
                existing.setUserId(UUID.randomUUID().toString());
                repository.save(existing);
            }
            return;
        }
        UserAccountEntity entity = new UserAccountEntity();
        entity.setUserId(UUID.randomUUID().toString());
        entity.setUsername(username);
        entity.setPasswordHash(passwordHasher.hash(defaultPassword));
        entity.setStatus(UserAccountStatus.ACTIVE);
        repository.save(entity);
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public record AuthRequest(String username, String password) {}

    public record AuthResponse(String token, UserView user) {}

    public record UserView(String userId, String username, boolean authenticated) {
        static UserView from(UserAccountEntity entity) {
            return new UserView(entity.getUserId(), entity.getUsername(), true);
        }
    }
}
