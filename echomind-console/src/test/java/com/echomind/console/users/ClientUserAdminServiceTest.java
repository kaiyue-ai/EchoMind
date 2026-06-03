package com.echomind.console.users;

import com.echomind.console.auth.UserAccountEntity;
import com.echomind.console.auth.UserAccountMapper;
import com.echomind.console.auth.UserAccountStatus;
import com.echomind.console.quota.TokenQuotaMapper;
import com.echomind.console.quota.TokenQuotaUsageMapper;
import com.echomind.console.usage.AiCallUsageMapper;
import com.echomind.console.users.ClientUserAdminDtos.UpdateClientUserStatusRequest;
import com.echomind.memory.persistence.mapper.ChatMessageMapper;
import com.echomind.memory.persistence.mapper.ChatSessionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientUserAdminServiceTest {

    @Test
    void updatesClientUserStatus() {
        UserAccountEntity user = user("user-a", "alice");
        UserAccountMapper userMapper = mock(UserAccountMapper.class);
        when(userMapper.selectOptionalById("user-a")).thenReturn(Optional.of(user));
        when(userMapper.upsertById(user)).thenReturn(user);
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiCallUsageMapper usageMapper = mock(AiCallUsageMapper.class);
        when(sessionMapper.countByUserId("user-a")).thenReturn(2L);
        when(messageMapper.countByUserId("user-a")).thenReturn(8L);
        ClientUserAdminService service = service(userMapper, sessionMapper, messageMapper, usageMapper,
            mock(TokenQuotaMapper.class), mock(TokenQuotaUsageMapper.class), mock(ClientUserRedisCleanupService.class));

        var view = service.updateStatus("user-a", new UpdateClientUserStatusRequest(UserAccountStatus.DISABLED));

        assertThat(view.status()).isEqualTo(UserAccountStatus.DISABLED);
        assertThat(view.active()).isFalse();
        assertThat(view.sessionCount()).isEqualTo(2);
        assertThat(view.messageCount()).isEqualTo(8);
        verify(userMapper).upsertById(user);
    }

    @Test
    void deletesClientUserAndOwnedData() {
        UserAccountEntity user = user("user-a", "alice");
        UserAccountMapper userMapper = mock(UserAccountMapper.class);
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiCallUsageMapper usageMapper = mock(AiCallUsageMapper.class);
        TokenQuotaMapper quotaMapper = mock(TokenQuotaMapper.class);
        TokenQuotaUsageMapper quotaUsageMapper = mock(TokenQuotaUsageMapper.class);
        ClientUserRedisCleanupService redisCleanupService = mock(ClientUserRedisCleanupService.class);
        when(userMapper.selectOptionalById("user-a")).thenReturn(Optional.of(user));
        when(sessionMapper.selectSessionIdsByUserId("user-a")).thenReturn(List.of("session-a", "session-b"));
        when(sessionMapper.countByUserId("user-a")).thenReturn(2L);
        when(messageMapper.countByUserId("user-a")).thenReturn(2L);
        when(usageMapper.countByUserId("user-a")).thenReturn(3L);
        when(quotaMapper.existsById("user-a")).thenReturn(true);
        when(quotaUsageMapper.countByUserId("user-a")).thenReturn(2L);
        when(redisCleanupService.cleanup("user-a", List.of("session-a", "session-b"))).thenReturn(5L);
        ClientUserAdminService service = service(userMapper, sessionMapper, messageMapper, usageMapper,
            quotaMapper, quotaUsageMapper, redisCleanupService);

        var response = service.delete("user-a");

        assertThat(response.deleted().sessions()).isEqualTo(2);
        assertThat(response.deleted().messages()).isEqualTo(2);
        assertThat(response.deleted().usageRows()).isEqualTo(3);
        assertThat(response.deleted().quotas()).isEqualTo(3);
        assertThat(response.deleted().redisKeys()).isEqualTo(5);
        verify(usageMapper).deleteByUserId("user-a");
        verify(quotaMapper).deleteById("user-a");
        verify(quotaUsageMapper).deleteByUserId("user-a");
        verify(messageMapper).deleteByUserId("user-a");
        verify(sessionMapper).deleteByUserId("user-a");
        verify(userMapper).deleteEntity(user);
    }

    private ClientUserAdminService service(UserAccountMapper userMapper,
                                           ChatSessionMapper sessionMapper,
                                           ChatMessageMapper messageMapper,
                                           AiCallUsageMapper usageMapper,
                                           TokenQuotaMapper quotaMapper,
                                           TokenQuotaUsageMapper quotaUsageMapper,
                                           ClientUserRedisCleanupService redisCleanupService) {
        return new ClientUserAdminService(
            userMapper,
            sessionMapper,
            messageMapper,
            usageMapper,
            quotaMapper,
            quotaUsageMapper,
            redisCleanupService
        );
    }

    private UserAccountEntity user(String userId, String username) {
        UserAccountEntity user = new UserAccountEntity();
        user.setUserId(userId);
        user.setUsername(username);
        user.setStatus(UserAccountStatus.ACTIVE);
        return user;
    }
}
