package com.echomind.console.dashboard;

import com.echomind.console.auth.UserAccountRepository;
import com.echomind.console.auth.UserAccountStatus;
import com.echomind.console.alerts.AlertEventRepository;
import com.echomind.console.sensitive.SensitiveEventRepository;
import com.echomind.console.usage.AiCallUsageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminDashboardServiceTest {

    @Test
    void buildsDashboardFromProviderUsageOnlyRepositoryData() {
        AiCallUsageRepository usageRepository = mock(AiCallUsageRepository.class);
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        when(userRepository.count()).thenReturn(2L);
        when(userRepository.countByStatus(UserAccountStatus.ACTIVE)).thenReturn(1L);
        when(userRepository.countByStatus(UserAccountStatus.DISABLED)).thenReturn(1L);
        when(usageRepository.globalTotals()).thenReturn(new Object[]{10L, 5L, 15L, 3L});
        when(usageRepository.totalsSince(any(Instant.class))).thenReturn(new Object[]{8L, 4L, 12L, 2L});
        when(usageRepository.averageDurationMs()).thenReturn(1200.0);
        when(usageRepository.averageDurationMsSince(any(Instant.class))).thenReturn(900.0);
        List<Object[]> modelRows = Collections.singletonList(new Object[]{"gpt-5.5", 2L, 8L, 4L, 12L, 900.0});
        when(usageRepository.modelTotalsSince(any(Instant.class))).thenReturn(modelRows);
        when(usageRepository.dailyTrendSince(any(Instant.class))).thenReturn(List.of());
        when(usageRepository.countByUsageSourceAndStatusAndCreatedAtGreaterThanEqual(any(), any(), any(Instant.class)))
            .thenReturn(0L);
        when(usageRepository.findByUsageSourceAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            any(), any(Instant.class), any(Pageable.class))).thenReturn(List.of());
        AdminDashboardService service = new AdminDashboardService(
            usageRepository,
            userRepository,
            mock(SensitiveEventRepository.class),
            mock(AlertEventRepository.class)
        );

        var dashboard = service.dashboard("7d");

        assertThat(dashboard.summary().totalUsers()).isEqualTo(2);
        assertThat(dashboard.summary().activeUsers()).isEqualTo(1);
        assertThat(dashboard.summary().disabledUsers()).isEqualTo(1);
        assertThat(dashboard.summary().totalTokens().totalTokens()).isEqualTo(15);
        assertThat(dashboard.summary().rangeTokens().totalTokens()).isEqualTo(12);
        assertThat(dashboard.summary().todayTokens().totalTokens()).isEqualTo(12);
        assertThat(dashboard.summary().averageDurationMs()).isEqualTo(1200);
        assertThat(dashboard.modelDistribution()).hasSize(1);
        assertThat(dashboard.modelDistribution().get(0).modelId()).isEqualTo("gpt-5.5");
        assertThat(dashboard.modelDistribution().get(0).totalTokens()).isEqualTo(12);
        verifyRecentCallsWereLimited(usageRepository);
    }

    private void verifyRecentCallsWereLimited(AiCallUsageRepository usageRepository) {
        org.mockito.Mockito.verify(usageRepository)
            .findByUsageSourceAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                eq(com.echomind.console.usage.TokenUsageSource.PROVIDER),
                any(Instant.class),
                any(Pageable.class)
            );
    }
}
