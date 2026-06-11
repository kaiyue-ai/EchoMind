package com.echomind.console.budget;

import com.echomind.console.alerts.AlertService;
import com.echomind.console.reservation.TokenReservationService;
import com.echomind.console.usage.AiCallUsageEntity;
import com.echomind.console.usage.AiCallUsageMapper;
import com.echomind.llm.router.ModelProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProviderTokenBudgetServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-06-03T10:15:30Z"),
        ZoneId.of("Asia/Shanghai")
    );

    @Test
    void reservesProviderBudgetWithEstimatedTokens() {
        ProviderTokenBudgetMapper budgetMapper = mock(ProviderTokenBudgetMapper.class);
        when(budgetMapper.selectOptionalById("deepseek")).thenReturn(Optional.of(activeBudget("deepseek")));
        ProviderTokenBudgetUsageMapper usageMapper = mock(ProviderTokenBudgetUsageMapper.class);
        TokenReservationService reservationService = mock(TokenReservationService.class);
        when(reservationService.reserveProvider("deepseek", "request-a", 4496L))
            .thenReturn(List.of("reservation-provider"));
        ProviderTokenBudgetService service = new ProviderTokenBudgetService(
            budgetMapper,
            mock(AiCallUsageMapper.class),
            usageMapper,
            mock(AlertService.class),
            new ModelProviderRegistry(),
            objectProvider(reservationService),
            FIXED_CLOCK
        );

        List<String> reservationIds = service.reserveProviderBudget("deepseek", "request-a", "planner",
            "session-a", 4496L);

        assertThat(reservationIds).containsExactly("reservation-provider");
        verify(reservationService).reserveProvider("deepseek", "request-a", 4496L);
        verifyNoInteractions(usageMapper);
    }

    @Test
    void recordsProviderUsageIntoDailyWeeklyAndMonthlyBuckets() {
        ProviderTokenBudgetMapper budgetMapper = mock(ProviderTokenBudgetMapper.class);
        ProviderTokenBudgetEntity budget = activeBudget("deepseek");
        when(budgetMapper.selectOptionalById("deepseek")).thenReturn(Optional.of(budget));
        ProviderTokenBudgetUsageMapper usageMapper = mock(ProviderTokenBudgetUsageMapper.class);
        ProviderTokenBudgetService service = new ProviderTokenBudgetService(
            budgetMapper,
            mock(AiCallUsageMapper.class),
            usageMapper,
            mock(AlertService.class),
            new ModelProviderRegistry(),
            objectProvider(null),
            FIXED_CLOCK
        );
        AiCallUsageEntity usage = new AiCallUsageEntity();
        usage.setProviderId("deepseek");
        usage.setTraceId("trace-a");
        usage.setAgentId("default");
        usage.setSessionId("session-a");
        usage.setTotalTokens(16);

        service.recordUsageAndWarnings(usage);

        LocalDate day = LocalDate.of(2026, 6, 3);
        LocalDate week = LocalDate.of(2026, 6, 1);
        LocalDate month = LocalDate.of(2026, 6, 1);
        verify(usageMapper).insertIgnoreBucket("deepseek", "daily", day);
        verify(usageMapper).incrementUsedTokens("deepseek", "daily", day, 16);
        verify(usageMapper).insertIgnoreBucket("deepseek", "weekly", week);
        verify(usageMapper).incrementUsedTokens("deepseek", "weekly", week, 16);
        verify(usageMapper).insertIgnoreBucket("deepseek", "monthly", month);
        verify(usageMapper).incrementUsedTokens("deepseek", "monthly", month, 16);
    }

    @Test
    void listReadsUsageFromProviderBudgetLedger() {
        ProviderTokenBudgetMapper budgetMapper = mock(ProviderTokenBudgetMapper.class);
        ProviderTokenBudgetEntity budget = activeBudget("deepseek");
        when(budgetMapper.selectAllOrderByProviderIdAsc()).thenReturn(List.of(budget));
        when(budgetMapper.selectOptionalById("deepseek")).thenReturn(Optional.of(budget));
        AiCallUsageMapper aiUsageMapper = mock(AiCallUsageMapper.class);
        when(aiUsageMapper.providerIdsWithUsage()).thenReturn(List.of());
        ProviderTokenBudgetUsageMapper usageMapper = mock(ProviderTokenBudgetUsageMapper.class);
        when(usageMapper.selectUsedTokens("deepseek", "daily", LocalDate.of(2026, 6, 3))).thenReturn(100L);
        when(usageMapper.selectUsedTokens("deepseek", "weekly", LocalDate.of(2026, 6, 1))).thenReturn(200L);
        when(usageMapper.selectUsedTokens("deepseek", "monthly", LocalDate.of(2026, 6, 1))).thenReturn(300L);
        ProviderTokenBudgetService service = new ProviderTokenBudgetService(
            budgetMapper,
            aiUsageMapper,
            usageMapper,
            mock(AlertService.class),
            new ModelProviderRegistry(),
            objectProvider(null),
            FIXED_CLOCK
        );

        ProviderTokenBudgetDtos.ProviderTokenBudgetView view = service.list().budgets().get(0);

        assertThat(view.providerId()).isEqualTo("deepseek");
        assertThat(view.todayUsedTokens()).isEqualTo(100);
        assertThat(view.weekUsedTokens()).isEqualTo(200);
        assertThat(view.monthUsedTokens()).isEqualTo(300);
        assertThat(view.dailyUsagePercent()).isEqualTo(10.0);
        assertThat(view.weeklyUsagePercent()).isEqualTo(10.0);
        assertThat(view.monthlyUsagePercent()).isEqualTo(10.0);
    }

    private ProviderTokenBudgetEntity activeBudget(String providerId) {
        ProviderTokenBudgetEntity budget = new ProviderTokenBudgetEntity();
        budget.setProviderId(providerId);
        budget.setDailyLimitTokens(1000L);
        budget.setWeeklyLimitTokens(2000L);
        budget.setMonthlyLimitTokens(3000L);
        budget.setWarningThresholdPercent(80);
        budget.setStatus(ProviderTokenBudgetStatus.ACTIVE);
        return budget;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<TokenReservationService> objectProvider(TokenReservationService service) {
        ObjectProvider<TokenReservationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }
}
