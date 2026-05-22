package com.echomind.console.quota;

import com.echomind.console.auth.AuthUser;
import com.echomind.console.auth.UserAccountRepository;
import com.echomind.console.usage.AiCallUsageRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenQuotaServiceTest {

    @Test
    void allowsUserWhenQuotaIsNotConfigured() {
        TokenQuotaRepository quotaRepository = mock(TokenQuotaRepository.class);
        when(quotaRepository.findById("user-a")).thenReturn(Optional.empty());
        AiCallUsageRepository usageRepository = mock(AiCallUsageRepository.class);
        TokenQuotaService service = new TokenQuotaService(
            quotaRepository,
            mock(UserAccountRepository.class),
            usageRepository
        );

        service.assertAllowed(new AuthUser("user-a", "alice", true));

        verify(usageRepository, never()).totalTokensByUserIdSince(any(), any());
    }

    @Test
    void blocksUserWhenDailyQuotaIsReached() {
        TokenQuotaEntity quota = new TokenQuotaEntity();
        quota.setUserId("user-a");
        quota.setDailyLimitTokens(100L);
        quota.setMonthlyLimitTokens(1000L);
        quota.setStatus(TokenQuotaStatus.ACTIVE);

        TokenQuotaRepository quotaRepository = mock(TokenQuotaRepository.class);
        when(quotaRepository.findById("user-a")).thenReturn(Optional.of(quota));
        AiCallUsageRepository usageRepository = mock(AiCallUsageRepository.class);
        when(usageRepository.totalTokensByUserIdSince(any(), any())).thenReturn(100L, 100L);
        TokenQuotaService service = new TokenQuotaService(
            quotaRepository,
            mock(UserAccountRepository.class),
            usageRepository
        );

        assertThatThrownBy(() -> service.assertAllowed(new AuthUser("user-a", "alice", true)))
            .isInstanceOf(TokenQuotaExceededException.class)
            .hasMessageContaining("daily");
    }
}
