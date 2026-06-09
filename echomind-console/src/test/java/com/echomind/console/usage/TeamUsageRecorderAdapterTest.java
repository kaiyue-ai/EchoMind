package com.echomind.console.usage;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.team.runtime.TeamProviderBudgetExceededException;
import com.echomind.agent.team.runtime.TeamUsageQuotaExceededException;
import com.echomind.agent.team.runtime.TeamUsageReservation;
import com.echomind.common.model.TokenUsage;
import com.echomind.console.alerts.AlertService;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.budget.ProviderTokenBudgetExceededException;
import com.echomind.console.budget.ProviderTokenBudgetService;
import com.echomind.console.quota.TokenQuotaExceededException;
import com.echomind.console.quota.TokenQuotaService;
import com.echomind.console.service.ChatProviderResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TeamUsageRecorderAdapterTest {

    @Test
    void recordsTeamUsageForRunCreator() {
        AiCallUsageService usageService = mock(AiCallUsageService.class);
        TokenQuotaService quotaService = mock(TokenQuotaService.class);
        AlertService alertService = mock(AlertService.class);
        TeamUsageRecorderAdapter adapter = adapter(usageService, quotaService, alertService);
        PipelineContext ctx = new PipelineContext();
        ctx.setTraceId("trace-a");
        ctx.setModelId("mock:model");
        ctx.setTokenUsage(new TokenUsage(7, 3, 10));
        AiCallUsageEntity usage = new AiCallUsageEntity();
        usage.setTraceId("trace-a");
        usage.setAgentId("planner");
        usage.setSessionId("session-a");
        when(usageService.recordSuccess(eq("echomind.team.planner"), any(AuthUser.class), eq(ctx), eq(100L)))
            .thenReturn(usage);

        adapter.record("echomind.team.planner", "user-a", "planner", "session-a", ctx, 100L, false, null);

        verify(usageService).recordSuccess(eq("echomind.team.planner"), eq(new AuthUser("user-a", "user-a", true)),
            eq(ctx), eq(100L));
        verifyNoInteractions(alertService);
    }

    @Test
    void recordDoesNotTranslatePostCallQuotaSettlementFailure() {
        AiCallUsageService usageService = mock(AiCallUsageService.class);
        TokenQuotaService quotaService = mock(TokenQuotaService.class);
        AlertService alertService = mock(AlertService.class);
        TeamUsageRecorderAdapter adapter = adapter(usageService, quotaService, alertService);
        PipelineContext ctx = new PipelineContext();
        ctx.setTraceId("trace-a");
        ctx.setModelId("mock:model");
        ctx.setTokenUsage(new TokenUsage(7, 3, 10));
        TokenQuotaExceededException quotaError = new TokenQuotaExceededException("user-a", "daily", 116, 100);
        doThrow(quotaError).when(usageService)
            .recordSuccess(eq("echomind.team.planner"), any(AuthUser.class), eq(ctx), eq(100L));

        assertThatCode(() -> adapter.record("echomind.team.planner", "user-a", "planner", "session-a", ctx, 100L,
            false, null)).doesNotThrowAnyException();

        verify(usageService).recordSuccess(eq("echomind.team.planner"), eq(new AuthUser("user-a", "user-a", true)),
            eq(ctx), eq(100L));
        verifyNoInteractions(alertService);
    }

    @Test
    void translatesQuotaExceededToTeamRuntimeException() {
        AiCallUsageService usageService = mock(AiCallUsageService.class);
        TokenQuotaService quotaService = mock(TokenQuotaService.class);
        AlertService alertService = mock(AlertService.class);
        TeamUsageRecorderAdapter adapter = adapter(usageService, quotaService, alertService);
        AuthUser owner = new AuthUser("user-a", "user-a", true);
        TokenQuotaExceededException quotaError = new TokenQuotaExceededException("user-a", "daily", 100, 100);
        org.mockito.Mockito.doThrow(quotaError).when(quotaService).assertAllowed(owner);

        assertThatThrownBy(() -> adapter.assertAllowed("user-a", "planner", "session-a"))
            .isInstanceOf(TeamUsageQuotaExceededException.class)
            .hasMessageContaining("Token quota exceeded");
        verifyNoInteractions(alertService);
    }

    @Test
    void reservesUserAndProviderBudgetForTeamCall() {
        AiCallUsageService usageService = mock(AiCallUsageService.class);
        TokenQuotaService quotaService = mock(TokenQuotaService.class);
        AlertService alertService = mock(AlertService.class);
        ProviderTokenBudgetService providerBudgetService = mock(ProviderTokenBudgetService.class);
        ChatProviderResolver providerResolver = mock(ChatProviderResolver.class);
        TeamUsageRecorderAdapter adapter = adapter(usageService, quotaService, alertService, providerBudgetService,
            providerResolver);
        when(quotaService.reserveUsage(eq(new AuthUser("user-a", "user-a", true)), eq("session-a"), eq(4100L)))
            .thenReturn(List.of("user-reservation"));
        when(providerResolver.resolveProviderId("planner", null)).thenReturn(Optional.of("deepseek"));
        when(providerBudgetService.reserveProviderBudget("deepseek", "session-a", "planner", "session-a", 4100L))
            .thenReturn(List.of("provider-reservation"));

        TeamUsageReservation reservation = adapter.reserveUsage("user-a", "planner", "session-a", "abcdefghij");

        assertThat(reservation.userReservationIds()).containsExactly("user-reservation");
        assertThat(reservation.providerReservationIds()).containsExactly("provider-reservation");
        verify(quotaService).reserveUsage(eq(new AuthUser("user-a", "user-a", true)), eq("session-a"), eq(4100L));
        verify(providerBudgetService).reserveProviderBudget("deepseek", "session-a", "planner", "session-a", 4100L);
    }

    @Test
    void releasesUserReservationWhenProviderBudgetIsExceeded() {
        AiCallUsageService usageService = mock(AiCallUsageService.class);
        TokenQuotaService quotaService = mock(TokenQuotaService.class);
        AlertService alertService = mock(AlertService.class);
        ProviderTokenBudgetService providerBudgetService = mock(ProviderTokenBudgetService.class);
        ChatProviderResolver providerResolver = mock(ChatProviderResolver.class);
        TeamUsageRecorderAdapter adapter = adapter(usageService, quotaService, alertService, providerBudgetService,
            providerResolver);
        when(quotaService.reserveUsage(eq(new AuthUser("user-a", "user-a", true)), eq("session-a"), eq(4100L)))
            .thenReturn(List.of("user-reservation"));
        when(providerResolver.resolveProviderId("planner", null)).thenReturn(Optional.of("deepseek"));
        ProviderTokenBudgetExceededException providerError =
            new ProviderTokenBudgetExceededException("deepseek", "daily", 100, 100);
        when(providerBudgetService.reserveProviderBudget("deepseek", "session-a", "planner", "session-a", 4100L))
            .thenThrow(providerError);

        assertThatThrownBy(() -> adapter.reserveUsage("user-a", "planner", "session-a", "abcdefghij"))
            .isInstanceOf(TeamProviderBudgetExceededException.class)
            .hasCause(providerError);

        verify(usageService).releaseReservations(List.of("user-reservation"));
    }

    @Test
    void releasesTeamReservationsThroughUsageService() {
        AiCallUsageService usageService = mock(AiCallUsageService.class);
        TokenQuotaService quotaService = mock(TokenQuotaService.class);
        AlertService alertService = mock(AlertService.class);
        TeamUsageRecorderAdapter adapter = adapter(usageService, quotaService, alertService);

        adapter.releaseReservations(List.of("reservation-a"));

        verify(usageService).releaseReservations(List.of("reservation-a"));
    }

    private TeamUsageRecorderAdapter adapter(AiCallUsageService usageService, TokenQuotaService quotaService,
                                             AlertService alertService) {
        ChatProviderResolver providerResolver = mock(ChatProviderResolver.class);
        when(providerResolver.resolveProviderId(any(), any())).thenReturn(Optional.empty());
        return adapter(usageService, quotaService, alertService, null, providerResolver);
    }

    private TeamUsageRecorderAdapter adapter(AiCallUsageService usageService, TokenQuotaService quotaService,
                                             AlertService alertService,
                                             ProviderTokenBudgetService providerBudgetService,
                                             ChatProviderResolver providerResolver) {
        return new TeamUsageRecorderAdapter(usageService, quotaService, alertService,
            objectProvider(providerBudgetService), providerResolver);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ProviderTokenBudgetService> objectProvider(ProviderTokenBudgetService service) {
        ObjectProvider<ProviderTokenBudgetService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }
}
