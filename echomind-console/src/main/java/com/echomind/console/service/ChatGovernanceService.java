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

    /**
     * 记录成功的 Token 使用。
     *
     * <p>核心流程：
     * 1. 检查模型是否返回了有效的 Token 使用数据
     * 2. 调用 AiCallUsageService 持久化到数据库
     * 3. 将使用数据记录到追踪 Span
     *
     * @param span        追踪 Span
     * @param operation   操作名称（如 echomind.chat.sync）
     * @param authUser    认证用户
     * @param ctx         管线上下文（包含 Token 使用数据）
     * @param startedNanos 调用开始时间（纳秒）
     * @return 持久化的使用记录实体，如果模型未返回有效数据则返回 null
     */
    public AiCallUsageEntity recordSuccessAndWarnings(Span span, String operation, AuthUser authUser,
                                                      PipelineContext ctx, long startedNanos) {
        if (modelUsageNotApplicable(ctx)) {
            return null;
        }

        AiCallUsageEntity usage = usageService.recordSuccess(operation, authUser, ctx, startedNanos);
        tagUsage(span, usage);
        return usage;
    }

    public void recordStreamUsage(Span span, String operation, AuthUser authUser, PipelineContext usageContext,
                                  long startedNanos, boolean error, String errorMessage) {
        if (!error && modelUsageNotApplicable(usageContext)) {
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
            }
        } catch (TokenQuotaExceededException e) {
            EchoMindTrace.recordException(span, e);
            throw e;
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
        quotaService.assertAllowed(authUser);
    }

    private void tagUsage(Span span, AiCallUsageEntity usage) {
        if (span == null || usage == null) {
            return;
        }
        span.setAttribute("echomind.user_id", safe(usage.getUserId()));
        span.setAttribute("echomind.account_type", usage.getAccountType());
        span.setAttribute("echomind.username", safe(usage.getUsername()));
        span.setAttribute("echomind.agent_id", safe(usage.getAgentId()));
        span.setAttribute("echomind.session_id", safe(usage.getSessionId()));
        span.setAttribute("echomind.model_id", safe(usage.getModelId()));
        span.setAttribute("echomind.provider_id", safe(usage.getProviderId()));
        span.setAttribute("echomind.prompt_tokens", usage.getPromptTokens());
        span.setAttribute("echomind.completion_tokens", usage.getCompletionTokens());
        span.setAttribute("echomind.total_tokens", usage.getTotalTokens());
        span.setAttribute("echomind.usage_source", usage.getUsageSource().name());
    }

    private boolean modelUsageNotApplicable(PipelineContext ctx) {
        return ctx != null
            && Boolean.TRUE.equals(ctx.getAttributes().get(PipelineContext.ATTR_MODEL_USAGE_NOT_APPLICABLE));
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
