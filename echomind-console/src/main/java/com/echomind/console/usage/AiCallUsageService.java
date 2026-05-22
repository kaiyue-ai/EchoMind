package com.echomind.console.usage;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.TokenUsage;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.auth.UserAccountRepository;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AiCallUsageService {

    private final AiCallUsageRepository repository;
    private final UserAccountRepository userRepository;

    @Transactional
    public AiCallUsageEntity recordSuccess(String operation, AuthUser user, PipelineContext ctx, long startedNanos) {
        return record(operation, user, ctx, startedNanos, "OK", null);
    }

    @Transactional
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
        entity.setTotalTokens(entity.getPromptTokens() + entity.getCompletionTokens());
        entity.setDurationMs(durationMs(startedNanos));
        tagCurrentSpan(entity);
        return repository.save(entity);
    }

    private String username(AuthUser user) {
        if (user == null || !user.authenticated()) {
            return "default";
        }
        return userRepository.findById(user.userId())
            .map(entity -> entity.getUsername())
            .orElse(user.username());
    }

    private void tagCurrentSpan(AiCallUsageEntity usage) {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) {
            return;
        }
        span.setAttribute("echomind.account_type", usage.getAccountType());
        span.setAttribute("echomind.username", blankToFallback(usage.getUsername(), ""));
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

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
