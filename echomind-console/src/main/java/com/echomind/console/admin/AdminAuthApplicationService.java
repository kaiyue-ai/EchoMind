package com.echomind.console.admin;

import com.echomind.console.auth.PasswordHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminAuthApplicationService {

    private final AdminUserMapper adminUserMapper;
    private final PasswordHasher passwordHasher;
    private final AdminTokenService tokenService;

    @Value("${echomind.admin.default-username:${ECHOMIND_ADMIN_DEFAULT_USERNAME:admin}}")
    private String defaultUsername;

    @Value("${echomind.admin.default-password:${ECHOMIND_ADMIN_DEFAULT_PASSWORD:admin123}}")
    private String defaultPassword;

    @Transactional
    public AdminAuthResponse login(AdminAuthRequest request) {
        ensureDefaultAdmin();
        String username = normalizeUsername(request == null ? null : request.username());
        String password = request == null ? null : request.password();
        AdminUserEntity user = adminUserMapper.selectByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (user.getStatus() != AdminUserStatus.ACTIVE || !passwordHasher.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        AdminUser adminUser = new AdminUser(user.getAdminId(), user.getUsername(), true);
        return new AdminAuthResponse(tokenService.issue(adminUser), userView(user));
    }

    @Transactional(readOnly = true)
    public AdminUser requireActiveAdmin(AdminUser tokenUser) {
        if (tokenUser == null || !tokenUser.authenticated()) {
            return null;
        }
        return adminUserMapper.selectByAdminIdAndStatus(tokenUser.adminId(), AdminUserStatus.ACTIVE)
            .map(user -> new AdminUser(user.getAdminId(), user.getUsername(), true))
            .orElse(null);
    }

    @Transactional(readOnly = true)
    public AdminUserView current() {
        AdminUser user = AdminContext.current();
        if (user == null || !user.authenticated()) {
            throw new IllegalArgumentException("请先登录管理端");
        }
        return adminUserMapper.selectOptionalById(user.adminId())
            .filter(entity -> entity.getStatus() == AdminUserStatus.ACTIVE)
            .map(this::userView)
            .orElseThrow(() -> new IllegalArgumentException("管理端账号不存在或已禁用"));
    }

    @Transactional
    public void ensureDefaultAdmin() {
        String username = normalizeUsername(defaultUsername);
        if (adminUserMapper.selectByUsername(username).isPresent()) {
            return;
        }
        AdminUserEntity entity = new AdminUserEntity();
        entity.setAdminId(UUID.randomUUID().toString());
        entity.setUsername(username);
        entity.setPasswordHash(passwordHasher.hash(defaultPassword));
        entity.setStatus(AdminUserStatus.ACTIVE);
        adminUserMapper.upsertById(entity);
    }

    private AdminUserView userView(AdminUserEntity entity) {
        return new AdminUserView(entity.getAdminId(), entity.getUsername(), true);
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public record AdminAuthRequest(String username, String password) {
    }

    public record AdminAuthResponse(String token, AdminUserView user) {
    }

    public record AdminUserView(String adminId, String username, boolean authenticated) {
    }
}
