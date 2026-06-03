package com.echomind.console.usage;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.team.runtime.TeamUsageQuotaExceededException;
import com.echomind.agent.team.runtime.TeamUsageRecorder;
import com.echomind.console.alerts.AlertService;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.quota.TokenQuotaExceededException;
import com.echomind.console.quota.TokenQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        } catch (TokenQuotaExceededException e) {
            throw new TeamUsageQuotaExceededException(e.getMessage(), e);
        } catch (RuntimeException e) {
            log.warn("Failed to record team token usage operation={} user={} agent={} session={}: {}",
                operation, userId, agentId, sessionId, e.getMessage());
            if (error) {
                alertService.emitCallError(owner, ctx, errorMessage);
            }
        }
    }

    private AuthUser authUser(String userId) {
        String normalized = userId == null || userId.isBlank() ? AuthUser.DEFAULT_USER_ID : userId;
        boolean authenticated = !AuthUser.DEFAULT_USER_ID.equals(normalized);
        return new AuthUser(normalized, normalized, authenticated);
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
