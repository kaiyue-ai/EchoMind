package com.echomind.console.service;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.console.alerts.AlertService;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.quota.TokenQuotaExceededException;
import com.echomind.console.quota.TokenQuotaService;
import com.echomind.console.sensitive.SensitiveDataService;
import com.echomind.console.usage.AiCallUsageEntity;
import com.echomind.console.usage.AiCallUsageService;
import com.echomind.common.observability.EchoMindTrace;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatGovernanceService {

    private final AiCallUsageService usageService;
    private final TokenQuotaService quotaService;
    private final SensitiveDataService sensitiveDataService;
    private final AlertService alertService;

    public String inspectRequest(Span span, AuthUser authUser, String agentId, String sessionId, String message) {
        assertQuotaAllowed(span, authUser, agentId, sessionId);
        return sensitiveDataService.inspectRequest(
            authUser,
            EchoMindTrace.traceId(span),
            agentId,
            sessionId,
            message
        ).text();
    }

    public void inspectResponse(AuthUser authUser, PipelineContext ctx) {
        if (ctx == null || ctx.getFinalResponse() == null || ctx.getFinalResponse().isBlank()) {
            return;
        }
        ctx.setFinalResponse(inspectResponseText(authUser, ctx.getTraceId(), ctx.getAgentId(), ctx.getSessionId(),
            ctx.getFinalResponse()));
    }

    public String inspectResponseText(AuthUser authUser, String traceId, String agentId, String sessionId, String text) {
        return sensitiveDataService.inspectResponse(authUser, traceId, agentId, sessionId, text).text();
    }

    public boolean emitCallErrorIfFailed(AuthUser authUser, PipelineContext ctx) {
        if (!isFailed(ctx)) {
            return false;
        }
        emitCallError(authUser, ctx, errorMessage(ctx));
        return true;
    }

    public void emitCallError(AuthUser authUser, PipelineContext ctx, String errorMessage) {
        alertService.emitCallError(authUser, ctx, normalizeErrorMessage(errorMessage));
    }

    public AiCallUsageEntity recordSuccessAndWarnings(Span span, String operation, AuthUser authUser,
                                                      PipelineContext ctx, long startedNanos) {
        if (modelUsageNotApplicable(ctx)) {
            alertService.emitQuotaWarning(authUser, ctx.getTraceId(), ctx.getAgentId(),
                ctx.getSessionId(), quotaService.warningSignals(authUser));
            return null;
        }
        AiCallUsageEntity usage = usageService.recordSuccess(operation, authUser, ctx, startedNanos);
        tagUsage(span, usage);
        alertService.emitQuotaWarning(authUser, ctx.getTraceId(), ctx.getAgentId(),
            ctx.getSessionId(), quotaService.warningSignals(authUser));
        return usage;
    }

    public void recordErrorQuietly(Span span, String operation, AuthUser authUser, PipelineContext ctx,
                                   long startedNanos, String errorMessage) {
        try {
            tagUsage(span, usageService.recordError(operation, authUser, ctx, startedNanos,
                normalizeErrorMessage(errorMessage)));
        } catch (RuntimeException usageError) {
            EchoMindTrace.recordException(span, usageError);
            log.warn("Failed to record token usage trace={} user={} session={}: {}",
                ctx == null ? "" : ctx.getTraceId(),
                authUser == null ? "" : authUser.userId(),
                ctx == null ? "" : ctx.getSessionId(),
                usageError.getMessage());
        }
    }

    public void recordStreamUsage(Span span, String operation, AuthUser authUser, PipelineContext usageContext,
                                  long startedNanos, boolean error, String errorMessage) {
        if (!error && modelUsageNotApplicable(usageContext)) {
            alertService.emitQuotaWarning(authUser, usageContext.getTraceId(), usageContext.getAgentId(),
                usageContext.getSessionId(), quotaService.warningSignals(authUser));
            return;
        }
        try {
            AiCallUsageEntity usage = error
                ? usageService.recordError(operation, authUser, usageContext, startedNanos,
                    normalizeErrorMessage(errorMessage))
                : usageService.recordSuccess(operation, authUser, usageContext, startedNanos);
            tagUsage(span, usage);
            if (error) {
                emitCallError(authUser, usageContext, errorMessage);
            } else {
                emitCallErrorIfFailed(authUser, usageContext);
                alertService.emitQuotaWarning(authUser, usageContext.getTraceId(), usageContext.getAgentId(),
                    usageContext.getSessionId(), quotaService.warningSignals(authUser));
            }
        } catch (RuntimeException e) {
            EchoMindTrace.recordException(span, e);
            if (error || isFailed(usageContext)) {
                emitCallError(authUser, usageContext, error ? errorMessage : errorMessage(usageContext));
            }
            log.warn("Failed to record stream token usage trace={} user={} session={}: {}",
                usageContext == null ? "" : usageContext.getTraceId(),
                authUser == null ? "" : authUser.userId(),
                usageContext == null ? "" : usageContext.getSessionId(),
                e.getMessage());
        }
    }

    public boolean isFailed(PipelineContext ctx) {
        return ctx != null && ctx.hasFailed();
    }

    public String errorMessage(PipelineContext ctx) {
        if (ctx == null) {
            return "模型调用失败";
        }
        if (ctx.hasFailed()) {
            return ctx.effectiveFailureReason();
        }
        if (ctx.getFinalResponse() == null || ctx.getFinalResponse().isBlank()) {
            return "模型调用失败";
        }
        return ctx.getFinalResponse();
    }

    private void assertQuotaAllowed(Span span, AuthUser authUser, String agentId, String sessionId) {
        try {
            quotaService.assertAllowed(authUser);
        } catch (TokenQuotaExceededException e) {
            alertService.emitQuotaExceeded(authUser, EchoMindTrace.traceId(span), agentId, sessionId, e);
            throw e;
        }
    }

    private void tagUsage(Span span, AiCallUsageEntity usage) {
        if (span == null || usage == null) {
            return;
        }
        span.setAttribute("echomind.account_type", usage.getAccountType());
        span.setAttribute("echomind.username", safe(usage.getUsername()));
        span.setAttribute("echomind.prompt_tokens", usage.getPromptTokens());
        span.setAttribute("echomind.completion_tokens", usage.getCompletionTokens());
        span.setAttribute("echomind.total_tokens", usage.getTotalTokens());
        span.setAttribute("echomind.usage_source", usage.getUsageSource().name());
    }

    private boolean modelUsageNotApplicable(PipelineContext ctx) {
        return ctx != null && Boolean.TRUE.equals(ctx.getAttributes().get("modelUsageNotApplicable"));
    }

    private String normalizeErrorMessage(String value) {
        if (value == null || value.isBlank()) {
            return "模型调用失败";
        }
        String trimmed = value.trim();
        return trimmed.startsWith("[Error]") ? trimmed.substring("[Error]".length()).trim() : trimmed;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
