package com.echomind.console.usage;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.team.runtime.TeamProviderBudgetExceededException;
import com.echomind.agent.team.runtime.TeamUsageQuotaExceededException;
import com.echomind.agent.team.runtime.TeamUsageRecorder;
import com.echomind.agent.team.runtime.TeamUsageReservation;
import com.echomind.console.alerts.AlertService;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.budget.ProviderTokenBudgetExceededException;
import com.echomind.console.budget.ProviderTokenBudgetService;
import com.echomind.console.quota.TokenQuotaExceededException;
import com.echomind.console.quota.TokenQuotaService;
import com.echomind.console.reservation.TokenEstimator;
import com.echomind.console.service.ChatProviderResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 将 Team 内部 LLM 调用纳入客户端同一套 token 用量和配额治理。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamUsageRecorderAdapter implements TeamUsageRecorder {

    private final AiCallUsageService usageService;
    private final TokenQuotaService quotaService;
    private final AlertService alertService;
    private final ObjectProvider<ProviderTokenBudgetService> providerBudgetService;
    private final ChatProviderResolver providerResolver;

    @Value("${echomind.llm.maxTokens:${echomind.models.providers.deepseek.max-tokens:4096}}")
    private long maxOutputTokens = 4096;

    @Override
    public void assertAllowed(String userId, String agentId, String sessionId) {
        AuthUser owner = authUser(userId);
        try {
            quotaService.assertAllowed(owner);
        } catch (TokenQuotaExceededException e) {
            throw new TeamUsageQuotaExceededException(e.getMessage(), e);
        }
    }

    @Override
    public TeamUsageReservation reserveUsage(String userId, String agentId, String sessionId, String prompt) {
        String requestId = reservationRequestId(sessionId);
        long estimatedTokens = TokenEstimator.estimateProcessedTokens(prompt, maxOutputTokens);
        List<String> userReservationIds = List.of();
        try {
            userReservationIds = safeReservations(
                quotaService.reserveUsage(authUser(userId), requestId, estimatedTokens));
            List<String> providerReservationIds = resolveProviderId(agentId)
                .map(providerId -> reserveProviderBudget(providerId, requestId, agentId, sessionId, estimatedTokens))
                .orElse(List.of());
            return new TeamUsageReservation(userReservationIds, providerReservationIds);
        } catch (TokenQuotaExceededException e) {
            throw new TeamUsageQuotaExceededException(e.getMessage(), e);
        } catch (ProviderTokenBudgetExceededException e) {
            releaseReservationsQuietly(userReservationIds);
            throw new TeamProviderBudgetExceededException(e.getMessage(), e);
        } catch (RuntimeException e) {
            releaseReservationsQuietly(userReservationIds);
            throw e;
        }
    }

    @Override
    public void record(String operation, String userId, String agentId, String sessionId,
                       PipelineContext ctx, long startedNanos, boolean error, String errorMessage) {
        if (ctx == null) {
            return;
        }
        fillContext(ctx, userId, agentId, sessionId);
        AuthUser owner = authUser(userId);
        try {
            AiCallUsageEntity usage = error
                ? usageService.recordError(operation, owner, ctx, startedNanos, errorMessage)
                : usageService.recordSuccess(operation, owner, ctx, startedNanos);
            if (error) {
                alertService.emitCallError(owner, ctx, errorMessage);
            }
        } catch (IllegalStateException e) {
            log.warn("Skip team token usage without provider usage operation={} user={} agent={} session={}: {}",
                operation, userId, agentId, sessionId, e.getMessage());
            if (error) {
                alertService.emitCallError(owner, ctx, errorMessage);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to record team token usage operation={} user={} agent={} session={}: {}",
                operation, userId, agentId, sessionId, e.getMessage());
            if (error) {
                alertService.emitCallError(owner, ctx, errorMessage);
            }
        }
    }

    @Override
    public void releaseReservations(List<String> reservationIds) {
        usageService.releaseReservations(reservationIds);
    }

    private AuthUser authUser(String userId) {
        String normalized = userId == null || userId.isBlank() ? AuthUser.DEFAULT_USER_ID : userId;
        boolean authenticated = !AuthUser.DEFAULT_USER_ID.equals(normalized);
        return new AuthUser(normalized, normalized, authenticated);
    }

    private Optional<String> resolveProviderId(String agentId) {
        if (providerResolver == null) {
            return Optional.empty();
        }
        return providerResolver.resolveProviderId(agentId, null);
    }

    private List<String> reserveProviderBudget(String providerId, String requestId, String agentId, String sessionId,
                                               long estimatedTokens) {
        ProviderTokenBudgetService service = providerBudgetService == null ? null : providerBudgetService.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        return safeReservations(service.reserveProviderBudget(providerId, requestId, agentId, sessionId,
            estimatedTokens));
    }

    private String reservationRequestId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "team-internal" : sessionId;
    }

    private List<String> safeReservations(List<String> reservationIds) {
        return reservationIds == null ? List.of() : reservationIds;
    }

    private void releaseReservationsQuietly(List<String> reservationIds) {
        try {
            releaseReservations(reservationIds);
        } catch (RuntimeException releaseError) {
            log.warn("Failed to release team reservations after reservation error: {}", releaseError.getMessage());
        }
    }

    private void fillContext(PipelineContext ctx, String userId, String agentId, String sessionId) {
        ctx.setUserId(nonBlank(ctx.getUserId(), userId));
        ctx.setAgentId(nonBlank(ctx.getAgentId(), agentId));
        ctx.setSessionId(nonBlank(ctx.getSessionId(), sessionId));
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
