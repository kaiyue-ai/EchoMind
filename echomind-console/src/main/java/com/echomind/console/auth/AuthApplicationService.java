package com.echomind.console.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.echomind.skill.storage.ObjectStorageService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 登录用例服务。 */
@Service
@RequiredArgsConstructor
public class AuthApplicationService {

    private static final long MAX_AVATAR_SIZE = 2 * 1024 * 1024;
    private static final Set<String> SUPPORTED_AVATAR_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final UserAccountMapper userMapper;
    private final PasswordHasher passwordHasher;
    private final AuthTokenService tokenService;
    private final ObjectStorageService storageService;

    @Value("${echomind.auth.default-username:${ECHOMIND_AUTH_DEFAULT_USERNAME:admin}}")
    private String defaultUsername;

    @Value("${echomind.auth.default-password:${ECHOMIND_AUTH_DEFAULT_PASSWORD:admin123}}")
    private String defaultPassword;

    @Transactional
    public AuthResponse login(AuthRequest request) {
        ensureDefaultUser();
        String username = normalizeUsername(request == null ? null : request.username());
        String password = request == null ? null : request.password();
        UserAccountEntity user = userMapper.selectByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (user.getStatus() != UserAccountStatus.ACTIVE || !passwordHasher.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        AuthUser authUser = new AuthUser(user.getUserId(), user.getUsername(), true);
        return new AuthResponse(tokenService.issue(authUser), userView(user));
    }

    @Transactional
    public AuthResponse register(AuthRequest request) {
        String username = normalizeUsername(request == null ? null : request.username());
        String password = request == null ? null : request.password();
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password is required");
        }
        if (userMapper.selectByUsername(username).isPresent()) {
            throw new IllegalArgumentException("用户名已存在");
        }
        UserAccountEntity entity = new UserAccountEntity();
        entity.setUserId(java.util.UUID.randomUUID().toString());
        entity.setUsername(username);
        entity.setPasswordHash(passwordHasher.hash(password));
        entity.setStatus(UserAccountStatus.ACTIVE);
        userMapper.upsertById(entity);
        AuthUser authUser = new AuthUser(entity.getUserId(), entity.getUsername(), true);
        return new AuthResponse(tokenService.issue(authUser), userView(entity));
    }

    public UserView current() {
        AuthUser user = AuthContext.current();
        if (!user.authenticated()) {
            return UserView.defaultUser();
        }
        return userMapper.selectOptionalById(user.userId())
            .filter(entity -> entity.getStatus() == UserAccountStatus.ACTIVE)
            .map(this::userView)
            .orElse(new UserView(user.userId(), user.username(), true, null, null));
    }

    @Transactional
    public UserView uploadAvatar(MultipartFile file) {
        AuthUser current = AuthContext.current();
        if (!current.authenticated()) {
            throw new IllegalArgumentException("请先登录后再上传头像");
        }
        UserAccountEntity user = userMapper.selectOptionalById(current.userId())
            .filter(entity -> entity.getStatus() == UserAccountStatus.ACTIVE)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在或已禁用"));
        validateAvatar(file);
        String oldAvatarUri = user.getAvatarUri();
        try {
            String contentType = file.getContentType().toLowerCase(Locale.ROOT);
            String ext = extension(file.getOriginalFilename(), contentType);
            String key = "avatars/" + user.getUserId() + "/"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                + "/" + UUID.randomUUID() + ext;
            Path temp = Files.createTempFile("echomind-avatar-", ext);
            file.transferTo(temp.toFile());
            try {
                var stored = storageService.putObject(key, temp, contentType);
                user.setAvatarUri(stored.uri());
                userMapper.upsertById(user);
                deleteOldAvatarQuietly(oldAvatarUri);
                return userView(user);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("头像上传失败，请检查 OSS 配置或稍后重试", e);
        }
    }

    @Transactional(readOnly = true)
    public AuthUser requireActiveUser(AuthUser tokenUser) {
        if (tokenUser == null || !tokenUser.authenticated()) {
            return AuthUser.DEFAULT;
        }
        return userMapper.selectByUserIdAndStatus(tokenUser.userId(), UserAccountStatus.ACTIVE)
            .map(user -> new AuthUser(user.getUserId(), user.getUsername(), true))
            .orElse(null);
    }

    @Transactional
    public void ensureDefaultUser() {
        String username = normalizeUsername(defaultUsername);
        UserAccountEntity existing = userMapper.selectByUsername(username).orElse(null);
        if (existing != null) {
            if (AuthUser.DEFAULT_USER_ID.equals(existing.getUserId())) {
                existing.setUserId(UUID.randomUUID().toString());
                userMapper.upsertById(existing);
            }
            return;
        }
        UserAccountEntity entity = new UserAccountEntity();
        entity.setUserId(UUID.randomUUID().toString());
        entity.setUsername(username);
        entity.setPasswordHash(passwordHasher.hash(defaultPassword));
        entity.setStatus(UserAccountStatus.ACTIVE);
        userMapper.upsertById(entity);
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public record AuthRequest(String username, String password) {}

    private UserView userView(UserAccountEntity entity) {
        String avatarUri = entity.getAvatarUri();
        return new UserView(entity.getUserId(), entity.getUsername(), true, avatarUri, avatarUrl(avatarUri));
    }

    private String avatarUrl(String avatarUri) {
        if (avatarUri == null || avatarUri.isBlank() || !storageService.supports(avatarUri)) {
            return null;
        }
        return storageService.urlFor(avatarUri, Duration.ofDays(7));
    }

    private void validateAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("头像不能为空");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new IllegalArgumentException("头像不能超过 2MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !SUPPORTED_AVATAR_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("仅支持 JPG、PNG、GIF、WebP 头像");
        }
    }

    private void deleteOldAvatarQuietly(String oldAvatarUri) {
        if (oldAvatarUri == null || oldAvatarUri.isBlank() || !storageService.supports(oldAvatarUri)) {
            return;
        }
        try {
            storageService.deleteObject(oldAvatarUri);
        } catch (Exception ignored) {
        }
    }

    private String extension(String filename, String contentType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                String ext = filename.substring(dot).toLowerCase(Locale.ROOT);
                if (ext.matches("\\.[a-z0-9]{1,8}")) {
                    return ext;
                }
            }
        }
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }

    public record AuthResponse(String token, UserView user) {}

    public record UserView(String userId, String username, boolean authenticated, String avatarUri, String avatarUrl) {
        static UserView defaultUser() {
            return new UserView(AuthUser.DEFAULT_USER_ID, AuthUser.DEFAULT.username(), false, null, null);
        }
    }
}
