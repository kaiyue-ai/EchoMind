package com.echomind.console.usage;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.TokenUsage;
import com.echomind.console.budget.ProviderTokenBudgetService;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.auth.UserAccountMapper;
import com.echomind.console.quota.TokenQuotaExceededException;
import com.echomind.console.quota.TokenQuotaService;
import io.opentelemetry.api.trace.Span;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
public class AiCallUsageService {

    private final AiCallUsageMapper usageMapper;
    private final UserAccountMapper userMapper;
    private final ObjectProvider<ProviderTokenBudgetService> providerTokenBudgetService;
    private final ObjectProvider<TokenQuotaService> tokenQuotaService;

    @Autowired
    public AiCallUsageService(AiCallUsageMapper usageMapper,
                              UserAccountMapper userMapper,
                              ObjectProvider<ProviderTokenBudgetService> providerTokenBudgetService,
                              ObjectProvider<TokenQuotaService> tokenQuotaService) {
        this.usageMapper = usageMapper;
        this.userMapper = userMapper;
        this.providerTokenBudgetService = providerTokenBudgetService;
        this.tokenQuotaService = tokenQuotaService;
    }

    AiCallUsageService(AiCallUsageMapper usageMapper, UserAccountMapper userMapper) {
        this(usageMapper, userMapper, null, null);
    }

    AiCallUsageService(AiCallUsageMapper usageMapper,
                       UserAccountMapper userMapper,
                       ObjectProvider<TokenQuotaService> tokenQuotaService) {
        this(usageMapper, userMapper, null, tokenQuotaService);
    }

    @Transactional(noRollbackFor = TokenQuotaExceededException.class)
    public AiCallUsageEntity recordSuccess(String operation, AuthUser user, PipelineContext ctx, long startedNanos) {
        return record(operation, user, ctx, startedNanos, "OK", null);
    }

    @Transactional(noRollbackFor = TokenQuotaExceededException.class)
    public AiCallUsageEntity recordError(String operation, AuthUser user, PipelineContext ctx, long startedNanos,
                                         String errorMessage) {
        return record(operation, user, ctx, startedNanos, "ERROR", errorMessage);
    }

    private AiCallUsageEntity record(String operation, AuthUser user, PipelineContext ctx, long startedNanos,
                                     String status, String errorMessage) {
        if (ctx == null) {
            return null;
        }
        AuthUser owner = user == null ? AuthUser.DEFAULT : user;
        AiCallUsageEntity entity = new AiCallUsageEntity();
        entity.setTraceId(blankToFallback(ctx.getTraceId(), Span.current().getSpanContext().getTraceId()));
        entity.setUserId(blankToFallback(owner.userId(), AuthUser.DEFAULT_USER_ID));
        entity.setUsername(username(owner));
        entity.setAccountType("client");
        entity.setAgentId(ctx.getAgentId());
        entity.setSessionId(ctx.getSessionId());
        entity.setModelId(ctx.getModelId());
        entity.setProviderId(providerId(ctx));
        entity.setOperation(operation);
        entity.setStatus(status);
        entity.setErrorMessage(truncate(errorMessage, 1000));
        TokenUsage providerUsage = ctx.getTokenUsage();
        if (providerUsage == null || !providerUsage.hasTokens()) {
            throw new IllegalStateException("模型未返回原生 token usage，拒绝记录预估用量");
        }
        entity.setUsageSource(TokenUsageSource.PROVIDER);
        entity.setPromptTokens(providerUsage.promptTokens());
        entity.setCompletionTokens(providerUsage.completionTokens());
        entity.setTotalTokens(providerUsage.totalTokens());
        entity.setDurationMs(durationMs(startedNanos));
        AiCallUsageEntity saved = usageMapper.upsertById(entity);
        tagCurrentSpan(saved);
        ProviderTokenBudgetService budgetService = providerTokenBudgetService == null
            ? null
            : providerTokenBudgetService.getIfAvailable();
        if (budgetService != null) {
            budgetService.recordWarnings(saved);
        }
        TokenQuotaService quotaService = tokenQuotaService == null ? null : tokenQuotaService.getIfAvailable();
        if (quotaService != null) {
            quotaService.settleUsage(owner, providerUsage.totalTokens());
        }
        return saved;
    }

    private String username(AuthUser user) {
        if (user == null || !user.authenticated()) {
            return "default";
        }
        return userMapper.selectOptionalById(user.userId())
            .map(entity -> entity.getUsername())
            .orElse(user.username());
    }

    private void tagCurrentSpan(AiCallUsageEntity usage) {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) {
            return;
        }
        span.setAttribute("echomind.user_id", blankToFallback(usage.getUserId(), ""));
        span.setAttribute("echomind.account_type", usage.getAccountType());
        span.setAttribute("echomind.username", blankToFallback(usage.getUsername(), ""));
        span.setAttribute("echomind.agent_id", blankToFallback(usage.getAgentId(), ""));
        span.setAttribute("echomind.session_id", blankToFallback(usage.getSessionId(), ""));
        span.setAttribute("echomind.model_id", blankToFallback(usage.getModelId(), ""));
        span.setAttribute("echomind.provider_id", blankToFallback(usage.getProviderId(), ""));
        span.setAttribute("echomind.prompt_tokens", usage.getPromptTokens());
        span.setAttribute("echomind.completion_tokens", usage.getCompletionTokens());
        span.setAttribute("echomind.total_tokens", usage.getTotalTokens());
        span.setAttribute("echomind.usage_source", usage.getUsageSource().name());
    }

    private long durationMs(long startedNanos) {
        if (startedNanos <= 0) {
            return 0;
        }
        return Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String providerId(PipelineContext ctx) {
        if (ctx.getResolvedModel() != null && ctx.getResolvedModel().providerId() != null
            && !ctx.getResolvedModel().providerId().isBlank()) {
            return ctx.getResolvedModel().providerId();
        }
        String modelId = ctx.getModelId();
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        int separator = modelId.indexOf(':');
        return separator > 0 ? modelId.substring(0, separator) : modelId;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
