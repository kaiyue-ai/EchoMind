package com.echomind.console.dashboard;

import com.echomind.console.auth.UserAccountMapper;
import com.echomind.console.auth.UserAccountStatus;
import com.echomind.console.alerts.AlertEventMapper;
import com.echomind.console.sensitive.SensitiveEventMapper;
import com.echomind.console.usage.AiCallUsageMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminDashboardServiceTest {

    @Test
    void buildsDashboardFromProviderUsageOnlyMapperData() {
        AiCallUsageMapper usageMapper = mock(AiCallUsageMapper.class);
        UserAccountMapper userMapper = mock(UserAccountMapper.class);
        when(userMapper.selectCountAll()).thenReturn(2L);
        when(userMapper.countByStatus(UserAccountStatus.ACTIVE)).thenReturn(1L);
        when(userMapper.countByStatus(UserAccountStatus.DISABLED)).thenReturn(1L);
        when(usageMapper.globalTotals()).thenReturn(new Object[]{10L, 5L, 15L, 3L});
        when(usageMapper.totalsSince(any(Instant.class))).thenReturn(new Object[]{8L, 4L, 12L, 2L});
        when(usageMapper.averageDurationMs()).thenReturn(1200.0);
        when(usageMapper.averageDurationMsSince(any(Instant.class))).thenReturn(900.0);
        List<Object[]> modelRows = Collections.singletonList(new Object[]{"gpt-5.5", 2L, 8L, 4L, 12L, 900.0});
        when(usageMapper.modelTotalsSince(any(Instant.class))).thenReturn(modelRows);
        when(usageMapper.dailyTrendSince(any(Instant.class))).thenReturn(List.of());
        when(usageMapper.countByUsageSourceAndStatusAndCreatedAtGreaterThanEqual(any(), any(), any(Instant.class)))
            .thenReturn(0L);
        when(usageMapper.findByUsageSourceAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            any(), any(Instant.class), anyInt())).thenReturn(List.of());
        AdminDashboardService service = new AdminDashboardService(
            usageMapper,
            userMapper,
            mock(SensitiveEventMapper.class),
            mock(AlertEventMapper.class)
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
        verifyRecentCallsWereLimited(usageMapper);
    }

    private void verifyRecentCallsWereLimited(AiCallUsageMapper usageMapper) {
        org.mockito.Mockito.verify(usageMapper)
            .findByUsageSourceAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                eq(com.echomind.console.usage.TokenUsageSource.PROVIDER),
                any(Instant.class),
                eq(8)
            );
    }
}
