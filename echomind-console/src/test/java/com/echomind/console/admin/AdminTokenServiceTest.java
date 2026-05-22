package com.echomind.console.admin;

import com.echomind.console.auth.AuthTokenService;
import com.echomind.console.auth.AuthUser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenServiceTest {

    @Test
    void issuesAdminJwtThatCannotBeUsedAsClientUserToken() {
        AdminTokenService adminTokens = new AdminTokenService("admin-secret", 3600);
        AuthTokenService userTokens = new AuthTokenService("user-secret", 3600);

        String adminToken = adminTokens.issue(new AdminUser("admin-a", "root", true));
        String userToken = userTokens.issue(new AuthUser("user-a", "alice", true));

        assertThat(adminToken.split("\\.")).hasSize(3);
        assertThat(json(adminToken.split("\\.")[1]))
            .contains("\"typ\":\"admin\"")
            .contains("\"adminId\":\"admin-a\"")
            .contains("\"username\":\"root\"");
        assertThat(adminTokens.verify(adminToken)).contains(new AdminUser("admin-a", "root", true));
        assertThat(userTokens.verify(adminToken)).isEmpty();
        assertThat(adminTokens.verify(userToken)).isEmpty();
    }

    private String json(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }
}
