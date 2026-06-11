package com.echomind.console.usage;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.TokenUsage;
import com.echomind.console.budget.ProviderTokenBudgetService;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.auth.UserAccountMapper;
import com.echomind.console.quota.TokenQuotaExceededException;
import com.echomind.console.quota.TokenQuotaService;
import com.echomind.console.reservation.TokenReservationService;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

/**
 * AI调用审计记录服务。
 * 负责记录每次模型调用的token使用量，并触发平台预算告警和用户配额结算。
 */
@Service
@Slf4j
public class AiCallUsageService {

    private final AiCallUsageMapper usageMapper;
    private final UserAccountMapper userMapper;
    private final ObjectProvider<ProviderTokenBudgetService> providerTokenBudgetService;
    private final ObjectProvider<TokenQuotaService> tokenQuotaService;
    private final ObjectProvider<TokenReservationService> tokenReservationService;

    @Autowired
    public AiCallUsageService(AiCallUsageMapper usageMapper,
                              UserAccountMapper userMapper,
                              ObjectProvider<ProviderTokenBudgetService> providerTokenBudgetService,
                              ObjectProvider<TokenQuotaService> tokenQuotaService,
                              ObjectProvider<TokenReservationService> tokenReservationService) {
        this.usageMapper = usageMapper;
        this.userMapper = userMapper;
        this.providerTokenBudgetService = providerTokenBudgetService;
        this.tokenQuotaService = tokenQuotaService;
        this.tokenReservationService = tokenReservationService;
    }

    public AiCallUsageService(AiCallUsageMapper usageMapper,
                              UserAccountMapper userMapper,
                              ObjectProvider<ProviderTokenBudgetService> providerTokenBudgetService,
                              ObjectProvider<TokenQuotaService> tokenQuotaService) {
        this(usageMapper, userMapper, providerTokenBudgetService, tokenQuotaService, null);
    }

    /** 测试用构造函数，不注入预算和配额服务 */
    AiCallUsageService(AiCallUsageMapper usageMapper, UserAccountMapper userMapper) {
        this(usageMapper, userMapper, null, null);
    }

    /** 测试用构造函数，只注入配额服务 */
    AiCallUsageService(AiCallUsageMapper usageMapper,
                       UserAccountMapper userMapper,
                       ObjectProvider<TokenQuotaService> tokenQuotaService) {
        this(usageMapper, userMapper, null, tokenQuotaService);
    }

    /**
     * 记录成功的模型调用。
     *
     * @param operation 操作名称
     * @param user 认证用户
     * @param ctx 管线上下文
     * @param startedNanos 调用开始时间（纳秒）
     * @return 保存的调用记录实体
     */
    @Transactional(noRollbackFor = TokenQuotaExceededException.class)
    public AiCallUsageEntity recordSuccess(String operation, AuthUser user, PipelineContext ctx, long startedNanos) {
        return record(operation, user, ctx, startedNanos, "OK", null);
    }

    /**
     * 记录失败的模型调用。
     *
     * @param operation 操作名称
     * @param user 认证用户
     * @param ctx 管线上下文
     * @param startedNanos 调用开始时间（纳秒）
     * @param errorMessage 错误信息
     * @return 保存的调用记录实体
     */
    @Transactional(noRollbackFor = TokenQuotaExceededException.class)
    public AiCallUsageEntity recordError(String operation, AuthUser user, PipelineContext ctx, long startedNanos,
                                         String errorMessage) {
        return record(operation, user, ctx, startedNanos, "ERROR", errorMessage);
    }

    /**
     * 核心记录方法：保存调用审计信息并触发预算检查和配额结算。
     *
     * @param operation 操作名称
     * @param user 认证用户
     * @param ctx 管线上下文
     * @param startedNanos 调用开始时间（纳秒）
     * @param status 调用状态（"OK"或"ERROR"）
     * @param errorMessage 错误信息
     * @return 保存的调用记录实体
     */
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
            releaseReservations(ctx);
            throw new IllegalStateException("模型未返回原生 token usage，拒绝记录预估用量");
        }
        entity.setUsageSource(TokenUsageSource.PROVIDER);
        entity.setPromptTokens(providerUsage.promptTokens());
        entity.setCompletionTokens(providerUsage.completionTokens());
        entity.setTotalTokens(providerUsage.totalTokens());

        entity.setDurationMs(durationMs(startedNanos));

        boolean reservationCompletionScheduled = false;
        try {
            AiCallUsageEntity saved = usageMapper.upsertById(entity);
            tagCurrentSpan(saved);

            ProviderTokenBudgetService budgetService = providerTokenBudgetService == null
                ? null
                : providerTokenBudgetService.getIfAvailable();
            if (budgetService != null) {
                budgetService.recordUsageAndWarnings(saved);
            }

            TokenQuotaService quotaService = tokenQuotaService == null ? null : tokenQuotaService.getIfAvailable();
            if (quotaService != null) {
                try {
                    quotaService.settleUsage(owner, providerUsage.totalTokens());
                } catch (TokenQuotaExceededException e) {
                    log.warn("Token quota settlement exceeded after completed call user={} operation={} tokens={}: {}",
                        owner.userId(), operation, providerUsage.totalTokens(), e.getMessage());
                }
            }

            completeReservationsAfterCommit(ctx, providerUsage.totalTokens());
            reservationCompletionScheduled = true;

            return saved;
        } catch (RuntimeException e) {
            if (!reservationCompletionScheduled) {
                releaseReservations(ctx);
            }
            throw e;
        }
    }

    public void releaseReservations(PipelineContext ctx) {
        if (ctx == null) {
            return;
        }
        releaseReservations(reservationIds(ctx, PipelineContext.ATTR_USER_TOKEN_RESERVATION_IDS));
        releaseReservations(reservationIds(ctx, PipelineContext.ATTR_PROVIDER_TOKEN_RESERVATION_IDS));
    }

    public void releaseReservations(List<String> reservationIds) {
        TokenReservationService service = reservationService();
        if (service != null) {
            service.release(reservationIds);
        }
    }

    private void settleReservations(PipelineContext ctx, long actualTokens) {
        TokenReservationService service = reservationService();
        if (service == null || ctx == null) {
            return;
        }
        service.settle(reservationIds(ctx, PipelineContext.ATTR_USER_TOKEN_RESERVATION_IDS), actualTokens);
        service.settle(reservationIds(ctx, PipelineContext.ATTR_PROVIDER_TOKEN_RESERVATION_IDS), actualTokens);
    }

    private void completeReservationsAfterCommit(PipelineContext ctx, long actualTokens) {
        if (ctx == null || reservationService() == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()
            || !TransactionSynchronizationManager.isActualTransactionActive()) {
            settleReservations(ctx, actualTokens);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                settleReservations(ctx, actualTokens);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    releaseReservations(ctx);
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private List<String> reservationIds(PipelineContext ctx, String attribute) {
        Object value = ctx.getAttributes().get(attribute);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private TokenReservationService reservationService() {
        return tokenReservationService == null ? null : tokenReservationService.getIfAvailable();
    }

    /**
     * 获取用户名，未认证用户返回"default"。
     */
    private String username(AuthUser user) {
        if (user == null || !user.authenticated()) {
            return "default";
        }
        return userMapper.selectOptionalById(user.userId())
            .map(entity -> entity.getUsername())
            .orElse(user.username());
    }

    /**
     * 将调用关键信息写入OpenTelemetry Span，方便分布式追踪。
     */
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

    /**
     * 计算调用耗时（毫秒）。
     */
    private long durationMs(long startedNanos) {
        if (startedNanos <= 0) {
            return 0;
        }
        return Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    /**
     * 如果字符串为空或null，返回默认值。
     */
    private String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 从上下文提取providerId：优先从已解析的模型获取，否则从modelId解析。
     */
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

    /**
     * 截断字符串到指定最大长度。
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
