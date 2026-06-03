package com.echomind.console.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.echomind.skill.storage.ObjectStorageService;
import com.echomind.skill.storage.StoredObject;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthApplicationServiceTest {

    @Test
    void loginCreatesAdminUserAndReturnsVerifiableToken() {
        UserAccountMapper mapper = inMemoryUserAccountMapper();
        PasswordHasher passwordHasher = new PasswordHasher();
        AuthTokenService tokenService = new AuthTokenService("test-secret", 3600);
        AuthApplicationService service = service(mapper, passwordHasher, tokenService);

        AuthApplicationService.AuthResponse response =
            service.login(new AuthApplicationService.AuthRequest("admin", "admin123"));

        assertThat(response.user().userId()).isNotBlank().isNotEqualTo(AuthUser.DEFAULT_USER_ID);
        assertThat(response.user().authenticated()).isTrue();
        assertThat(tokenService.verify(response.token()))
            .get()
            .extracting(AuthUser::userId)
            .isEqualTo(response.user().userId());
    }

    @Test
    void wrongPasswordIsRejected() {
        UserAccountMapper mapper = inMemoryUserAccountMapper();
        AuthApplicationService service = service(
            mapper,
            new PasswordHasher(),
            new AuthTokenService("test-secret", 3600)
        );

        assertThatThrownBy(() -> service.login(new AuthApplicationService.AuthRequest("admin", "wrong")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void registerCreatesIndependentUser() {
        UserAccountMapper mapper = inMemoryUserAccountMapper();
        AuthApplicationService service = service(
            mapper,
            new PasswordHasher(),
            new AuthTokenService("test-secret", 3600)
        );

        AuthApplicationService.AuthResponse response =
            service.register(new AuthApplicationService.AuthRequest("Alice", "s3cret"));

        assertThat(response.user().username()).isEqualTo("alice");
        assertThat(response.user().userId()).isNotBlank().isNotEqualTo(AuthUser.DEFAULT_USER_ID);
        assertThat(response.token()).isNotBlank();
    }

    @Test
    void uploadAvatarStoresFileAndUpdatesCurrentUser() throws Exception {
        UserAccountMapper mapper = inMemoryUserAccountMapper();
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        when(storageService.putObject(anyString(), any(Path.class), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            return new StoredObject("oss://bucket/" + key, "bucket", key, "https://cdn.example.com/" + key, 12, "image/png");
        });
        when(storageService.supports(anyString())).thenReturn(true);
        when(storageService.urlFor(anyString(), any())).thenReturn("https://cdn.example.com/avatar.png");
        AuthApplicationService service = service(mapper, new PasswordHasher(), new AuthTokenService("test-secret", 3600), storageService);
        AuthApplicationService.AuthResponse response =
            service.register(new AuthApplicationService.AuthRequest("AvatarUser", "s3cret"));
        AuthContext.set(new AuthUser(response.user().userId(), "avataruser", true));

        try {
            AuthApplicationService.UserView user = service.uploadAvatar(new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[] {1, 2, 3}
            ));

            assertThat(user.avatarUri()).startsWith("oss://bucket/avatars/" + response.user().userId());
            assertThat(user.avatarUrl()).isEqualTo("https://cdn.example.com/avatar.png");
            verify(storageService).putObject(org.mockito.ArgumentMatchers.startsWith("avatars/" + response.user().userId()),
                any(Path.class), org.mockito.ArgumentMatchers.eq("image/png"));
        } finally {
            AuthContext.clear();
        }
    }

    @Test
    void uploadAvatarRejectsFilesOverTwoMb() {
        UserAccountMapper mapper = inMemoryUserAccountMapper();
        AuthApplicationService service = service(mapper, new PasswordHasher(), new AuthTokenService("test-secret", 3600));
        AuthApplicationService.AuthResponse response =
            service.register(new AuthApplicationService.AuthRequest("LargeAvatar", "s3cret"));
        AuthContext.set(new AuthUser(response.user().userId(), "largeavatar", true));

        try {
            byte[] tooLarge = new byte[2 * 1024 * 1024 + 1];
            assertThatThrownBy(() -> service.uploadAvatar(new MockMultipartFile(
                "file", "avatar.png", "image/png", tooLarge
            )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("头像不能超过 2MB");
        } finally {
            AuthContext.clear();
        }
    }

    @Test
    void uploadAvatarHidesStorageProviderDetailsOnFailure() throws Exception {
        UserAccountMapper mapper = inMemoryUserAccountMapper();
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        when(storageService.putObject(anyString(), any(Path.class), anyString()))
            .thenThrow(new java.io.IOException("internal credential details should stay server-side"));
        AuthApplicationService service = service(mapper, new PasswordHasher(), new AuthTokenService("test-secret", 3600), storageService);
        AuthApplicationService.AuthResponse response =
            service.register(new AuthApplicationService.AuthRequest("FailAvatar", "s3cret"));
        AuthContext.set(new AuthUser(response.user().userId(), "failavatar", true));

        try {
            assertThatThrownBy(() -> service.uploadAvatar(new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[] {1, 2, 3}
            )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("头像上传失败，请检查 OSS 配置或稍后重试")
                .hasMessageNotContaining("credential")
                .hasMessageNotContaining("server-side");
        } finally {
            AuthContext.clear();
        }
    }

    @Test
    void requireActiveUserRejectsDisabledOrMissingUsers() {
        UserAccountMapper mapper = inMemoryUserAccountMapper();
        AuthApplicationService service = service(
            mapper,
            new PasswordHasher(),
            new AuthTokenService("test-secret", 3600)
        );
        AuthApplicationService.AuthResponse response =
            service.register(new AuthApplicationService.AuthRequest("Bob", "s3cret"));

        assertThat(service.requireActiveUser(new AuthUser(response.user().userId(), "bob", true)))
            .isEqualTo(new AuthUser(response.user().userId(), "bob", true));

        UserAccountEntity user = mapper.selectByUsername("bob").orElseThrow();
        user.setStatus(UserAccountStatus.DISABLED);

        assertThat(service.requireActiveUser(new AuthUser(response.user().userId(), "bob", true)))
            .isNull();
        assertThat(service.requireActiveUser(new AuthUser("missing", "ghost", true)))
            .isNull();
    }

    @Test
    void authFilterSkipsLoginAndRegisterEndpoints() {
        AuthFilter filter = new AuthFilter(
            new AuthTokenService("test-secret", 3600),
            service(inMemoryUserAccountMapper(), new PasswordHasher(), new AuthTokenService("test-secret", 3600))
        );

        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletRequest register = new MockHttpServletRequest("POST", "/api/auth/register");
        MockHttpServletRequest me = new MockHttpServletRequest("GET", "/api/auth/me");

        assertThat(filter.shouldNotFilter(login)).isTrue();
        assertThat(filter.shouldNotFilter(register)).isTrue();
        assertThat(filter.shouldNotFilter(me)).isFalse();
    }

    @Test
    void authFilterStoresJwtUserInThreadLocalAndClearsAfterRequest() throws Exception {
        UserAccountMapper mapper = inMemoryUserAccountMapper();
        PasswordHasher passwordHasher = new PasswordHasher();
        AuthTokenService tokenService = new AuthTokenService("test-secret", 3600);
        AuthApplicationService service = service(mapper, passwordHasher, tokenService);
        AuthApplicationService.AuthResponse login =
            service.register(new AuthApplicationService.AuthRequest("ThreadUser", "s3cret"));
        AuthFilter filter = new AuthFilter(tokenService, service);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + login.token());
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = (servletRequest, servletResponse) ->
            assertThat(AuthContext.current())
                .isEqualTo(new AuthUser(login.user().userId(), "threaduser", true))
        ;

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(AuthContext.current()).isEqualTo(AuthUser.DEFAULT);
    }

    @Test
    void authFilterRejectsInvalidJwtAndDoesNotLeakThreadLocal() throws Exception {
        AuthFilter filter = new AuthFilter(
            new AuthTokenService("test-secret", 3600),
            service(inMemoryUserAccountMapper(), new PasswordHasher(), new AuthTokenService("test-secret", 3600))
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer invalid.token.value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or expired token");
        assertThat(AuthContext.current()).isEqualTo(AuthUser.DEFAULT);
    }

    private static AuthApplicationService service(UserAccountMapper mapper,
                                                  PasswordHasher passwordHasher,
                                                  AuthTokenService tokenService) {
        return service(mapper, passwordHasher, tokenService, mock(ObjectStorageService.class));
    }

    private static AuthApplicationService service(UserAccountMapper mapper,
                                                  PasswordHasher passwordHasher,
                                                  AuthTokenService tokenService,
                                                  ObjectStorageService storageService) {
        AuthApplicationService service = new AuthApplicationService(mapper, passwordHasher, tokenService, storageService);
        ReflectionTestUtils.setField(service, "defaultUsername", "admin");
        ReflectionTestUtils.setField(service, "defaultPassword", "admin123");
        return service;
    }

    private static UserAccountMapper inMemoryUserAccountMapper() {
        java.util.Map<String, UserAccountEntity> byUsername = new java.util.LinkedHashMap<>();
        UserAccountMapper mapper = mock(UserAccountMapper.class);
        when(mapper.selectByUsername(any())).thenAnswer(invocation ->
            Optional.ofNullable(byUsername.get(invocation.getArgument(0, String.class)))
        );
        when(mapper.selectByUserIdAndStatus(any(), any())).thenAnswer(invocation -> {
            String userId = invocation.getArgument(0, String.class);
            UserAccountStatus status = invocation.getArgument(1, UserAccountStatus.class);
            return byUsername.values().stream()
                .filter(user -> userId.equals(user.getUserId()) && status == user.getStatus())
                .findFirst();
        });
        when(mapper.selectOptionalById(any())).thenAnswer(invocation -> {
            String userId = invocation.getArgument(0, String.class);
            return byUsername.values().stream()
                .filter(user -> userId.equals(user.getUserId()))
                .findFirst();
        });
        when(mapper.upsertById(any(UserAccountEntity.class))).thenAnswer(invocation -> {
            UserAccountEntity entity = invocation.getArgument(0, UserAccountEntity.class);
            byUsername.put(entity.getUsername(), entity);
            return entity;
        });
        return mapper;
    }
}
