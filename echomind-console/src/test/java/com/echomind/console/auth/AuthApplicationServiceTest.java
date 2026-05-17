package com.echomind.console.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthApplicationServiceTest {

    @Test
    void loginCreatesAdminUserAndReturnsVerifiableToken() {
        UserAccountRepository repository = inMemoryUserAccountRepository();
        PasswordHasher passwordHasher = new PasswordHasher();
        AuthTokenService tokenService = new AuthTokenService("test-secret", 3600);
        AuthApplicationService service = service(repository, passwordHasher, tokenService);

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
        UserAccountRepository repository = inMemoryUserAccountRepository();
        AuthApplicationService service = service(
            repository,
            new PasswordHasher(),
            new AuthTokenService("test-secret", 3600)
        );

        assertThatThrownBy(() -> service.login(new AuthApplicationService.AuthRequest("admin", "wrong")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void registerCreatesIndependentUser() {
        UserAccountRepository repository = inMemoryUserAccountRepository();
        AuthApplicationService service = service(
            repository,
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
    void requireActiveUserRejectsDisabledOrMissingUsers() {
        UserAccountRepository repository = inMemoryUserAccountRepository();
        AuthApplicationService service = service(
            repository,
            new PasswordHasher(),
            new AuthTokenService("test-secret", 3600)
        );
        AuthApplicationService.AuthResponse response =
            service.register(new AuthApplicationService.AuthRequest("Bob", "s3cret"));

        assertThat(service.requireActiveUser(new AuthUser(response.user().userId(), "bob", true)))
            .isEqualTo(new AuthUser(response.user().userId(), "bob", true));

        UserAccountEntity user = repository.findByUsername("bob").orElseThrow();
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
            service(inMemoryUserAccountRepository(), new PasswordHasher(), new AuthTokenService("test-secret", 3600))
        );

        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletRequest register = new MockHttpServletRequest("POST", "/api/auth/register");
        MockHttpServletRequest me = new MockHttpServletRequest("GET", "/api/auth/me");

        assertThat(filter.shouldNotFilter(login)).isTrue();
        assertThat(filter.shouldNotFilter(register)).isTrue();
        assertThat(filter.shouldNotFilter(me)).isFalse();
    }

    private static AuthApplicationService service(UserAccountRepository repository,
                                                  PasswordHasher passwordHasher,
                                                  AuthTokenService tokenService) {
        AuthApplicationService service = new AuthApplicationService(repository, passwordHasher, tokenService);
        ReflectionTestUtils.setField(service, "defaultUsername", "admin");
        ReflectionTestUtils.setField(service, "defaultPassword", "admin123");
        return service;
    }

    private static UserAccountRepository inMemoryUserAccountRepository() {
        java.util.Map<String, UserAccountEntity> byUsername = new java.util.LinkedHashMap<>();
        UserAccountRepository repository = mock(UserAccountRepository.class);
        when(repository.findByUsername(any())).thenAnswer(invocation ->
            Optional.ofNullable(byUsername.get(invocation.getArgument(0, String.class)))
        );
        when(repository.findByUserIdAndStatus(any(), any())).thenAnswer(invocation -> {
            String userId = invocation.getArgument(0, String.class);
            UserAccountStatus status = invocation.getArgument(1, UserAccountStatus.class);
            return byUsername.values().stream()
                .filter(user -> userId.equals(user.getUserId()) && status == user.getStatus())
                .findFirst();
        });
        when(repository.save(any(UserAccountEntity.class))).thenAnswer(invocation -> {
            UserAccountEntity entity = invocation.getArgument(0, UserAccountEntity.class);
            byUsername.put(entity.getUsername(), entity);
            return entity;
        });
        return repository;
    }
}
