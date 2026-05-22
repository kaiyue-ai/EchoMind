package com.echomind.console.users;

import com.echomind.console.auth.UserAccountEntity;
import com.echomind.console.auth.UserAccountRepository;
import com.echomind.console.auth.UserAccountStatus;
import com.echomind.console.quota.TokenQuotaRepository;
import com.echomind.console.usage.AiCallUsageRepository;
import com.echomind.console.users.ClientUserAdminDtos.UpdateClientUserStatusRequest;
import com.echomind.memory.persistence.ChatMessageRepository;
import com.echomind.memory.persistence.ChatSessionRepository;
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
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        when(userRepository.findById("user-a")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        AiCallUsageRepository usageRepository = mock(AiCallUsageRepository.class);
        when(sessionRepository.countByUserId("user-a")).thenReturn(2L);
        when(messageRepository.countByUserId("user-a")).thenReturn(8L);
        ClientUserAdminService service = service(userRepository, sessionRepository, messageRepository, usageRepository,
            mock(TokenQuotaRepository.class), mock(MemoryEmbeddingCleanupRepository.class),
            mock(ClientUserRedisCleanupService.class));

        var view = service.updateStatus("user-a", new UpdateClientUserStatusRequest(UserAccountStatus.DISABLED));

        assertThat(view.status()).isEqualTo(UserAccountStatus.DISABLED);
        assertThat(view.active()).isFalse();
        assertThat(view.sessionCount()).isEqualTo(2);
        assertThat(view.messageCount()).isEqualTo(8);
        verify(userRepository).save(user);
    }

    @Test
    void deletesClientUserAndOwnedData() {
        UserAccountEntity user = user("user-a", "alice");
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        AiCallUsageRepository usageRepository = mock(AiCallUsageRepository.class);
        TokenQuotaRepository quotaRepository = mock(TokenQuotaRepository.class);
        MemoryEmbeddingCleanupRepository embeddingRepository = mock(MemoryEmbeddingCleanupRepository.class);
        ClientUserRedisCleanupService redisCleanupService = mock(ClientUserRedisCleanupService.class);
        when(userRepository.findById("user-a")).thenReturn(Optional.of(user));
        when(sessionRepository.findSessionIdsByUserId("user-a")).thenReturn(List.of("session-a", "session-b"));
        when(messageRepository.findIdsByUserId("user-a")).thenReturn(List.of(1L, 2L));
        when(sessionRepository.countByUserId("user-a")).thenReturn(2L);
        when(messageRepository.countByUserId("user-a")).thenReturn(2L);
        when(usageRepository.countByUserId("user-a")).thenReturn(3L);
        when(quotaRepository.existsById("user-a")).thenReturn(true);
        when(embeddingRepository.deleteBySessionIds(List.of("session-a", "session-b"))).thenReturn(1);
        when(embeddingRepository.deleteByMessageIds(List.of(1L, 2L))).thenReturn(2);
        when(redisCleanupService.cleanup("user-a", List.of("session-a", "session-b"))).thenReturn(5L);
        ClientUserAdminService service = service(userRepository, sessionRepository, messageRepository, usageRepository,
            quotaRepository, embeddingRepository, redisCleanupService);

        var response = service.delete("user-a");

        assertThat(response.deleted().sessions()).isEqualTo(2);
        assertThat(response.deleted().messages()).isEqualTo(2);
        assertThat(response.deleted().usageRows()).isEqualTo(3);
        assertThat(response.deleted().quotas()).isEqualTo(1);
        assertThat(response.deleted().memoryEmbeddings()).isEqualTo(3);
        assertThat(response.deleted().redisKeys()).isEqualTo(5);
        verify(usageRepository).deleteByUserId("user-a");
        verify(quotaRepository).deleteById("user-a");
        verify(messageRepository).deleteByUserId("user-a");
        verify(sessionRepository).deleteByUserId("user-a");
        verify(userRepository).delete(user);
    }

    private ClientUserAdminService service(UserAccountRepository userRepository,
                                           ChatSessionRepository sessionRepository,
                                           ChatMessageRepository messageRepository,
                                           AiCallUsageRepository usageRepository,
                                           TokenQuotaRepository quotaRepository,
                                           MemoryEmbeddingCleanupRepository embeddingRepository,
                                           ClientUserRedisCleanupService redisCleanupService) {
        return new ClientUserAdminService(
            userRepository,
            sessionRepository,
            messageRepository,
            usageRepository,
            quotaRepository,
            embeddingRepository,
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
