package com.echomind.console.service;

import com.echomind.agent.orchestration.AgentOrchestrator.StreamExecution;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.exception.ModelInvocationRejectedException;
import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatResponse;
import com.echomind.common.model.ChatStreamEvent;
import com.echomind.common.model.ErrorDetail;
import com.echomind.common.observability.EchoMindTrace;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.budget.ProviderTokenBudgetExceededException;
import com.echomind.console.quota.TokenQuotaExceededException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 队列流式聊天执行器。
 * 
 * <p>负责执行从 RabbitMQ 队列中消费的流式聊天请求，并将结果通过事件回调推送给客户端。
 * 
 * <p>核心流程：
 * 1. 初始化追踪和上下文
 * 2. 调用 AgentChatExecutor 执行流式聊天
 * 3. 订阅 token 流，逐个推送给客户端
 * 4. 记录用量并返回最终响应
 */
@Service
@RequiredArgsConstructor
class QueuedChatStreamExecutor {

    /** 操作名称，用于追踪和日志 */
    private static final String OPERATION = "echomind.chat.stream.consume";

    /** Agent 聊天执行器 */
    private final AgentChatExecutor agentChatExecutor;
    /** 聊天治理服务（配额检查、用量记录等） */
    private final ChatGovernanceService governanceService;

    /**
     * 执行队列中的流式聊天请求。
     * 
     * @param request 聊天请求（包含用户ID、AgentID、消息等）
     * @param events 事件回调，用于推送 token 和元数据给客户端
     * @return ChatResponse 最终响应（成功或失败）
     */
    ChatResponse execute(ChatRequest request, Consumer<ChatStreamEvent> events) {
        // 1. 创建 OpenTelemetry 追踪 Span
        Span span = EchoMindTrace.startSpan(OPERATION, EchoMindTrace.extractContext(traceCarrier(request)));
        // 记录请求开始时间（用于计算耗时）
        long startedNanos = System.nanoTime();
        // 流式聊天上下文（用于记录用量和结果）
        PipelineContext usageContext = null;
        // 获取认证用户
        AuthUser authUser = queuedAuthUser(request);
        // 标记请求属性到追踪 Span
        tagRequest(span, request);
        // 流式执行结果
        StreamExecution execution;
        // 输出缓冲区（用于收集完整响应）
        StringBuilder output = new StringBuilder(); 

        // 设置追踪 Span 为当前上下文
        try (Scope ignored = span.makeCurrent()) {
            try {
                String rawMessage = request.message();
                
                // 2. 调用 AgentChatExecutor 执行流式聊天
                // .block() 阻塞等待获取 StreamExecution
                execution = agentChatExecutor.executeStream(request.userId(), request.agentId(), request.sessionId(),
                        request.message(), request.modelId(), request.attachments(), rawMessage,
                        requestReservationAttributes(request))
                    .block();
                
                // 检查执行结果是否为空
                if (execution == null) {
                    throw new IllegalStateException("stream execution was not created");
                }

                // 3. 获取执行上下文
                usageContext = execution.context();
                // 附加预留ID到上下文（用于后续结算）
                attachRequestReservations(usageContext, request);
                // 确保追踪ID存在
                ensureTraceId(usageContext, span);

                // 4. 设置工具进度回调（用于推送工具调用状态）
                usageContext.setToolProgressSink(event -> {
                    if (PipelineContext.ToolProgressEvent.TYPE_START.equals(event.type())) {
                        emit(events, ChatStreamEvent.toolStart(request.requestId(), event.toolName()));
                    } else if (PipelineContext.ToolProgressEvent.TYPE_END.equals(event.type())) {
                        emit(events, ChatStreamEvent.toolEnd(request.requestId(), event.toolName(), event.durationMs()));
                    }
                });

                // 5. 推送元数据事件（包含会话ID和追踪ID）
                emit(events, ChatStreamEvent.meta(request.requestId(), usageContext.getSessionId(), usageContext.getTraceId()));

                PipelineContext streamContext = usageContext;
                
                // 6. 订阅 token 流
                execution.tokens()
                    // 对每个 token 进行内容检查（敏感内容检测等）
                    .map(token -> governanceService.inspectResponseText(
                        authUser,
                        streamContext.getTraceId(),
                        streamContext.getAgentId(),
                        streamContext.getSessionId(),
                        token
                    ))
                    // 每个 token 到达时：追加到缓冲区并推送给客户端
                    .doOnNext(token -> {
                        output.append(token);
                        emit(events, ChatStreamEvent.token(request.requestId(), token));
                    })
                    // 错误处理：记录异常到追踪
                    .doOnError(error -> EchoMindTrace.recordException(span, error))
                    // 阻塞等待流结束
                    .blockLast();

                // 7. 设置最终响应（如果没有失败）
                if (!usageContext.hasFailed()) {
                    usageContext.setFinalResponse(output.toString());
                }

                // 8. 记录用量并返回响应
                boolean failed = usageContext.hasFailed();
                String failureReason = failed ? usageContext.effectiveFailureReason() : null;
                governanceService.recordStreamUsage(span, OPERATION, authUser, usageContext, startedNanos, failed,
                    failureReason);

                if (failed) {
                    return ChatResponse.error(request.requestId(), failureReason, usageContext.getTraceId(),
                        currentTraceparent());
                }

                return ChatResponse.success(
                    request.requestId(),
                    usageContext.getSessionId(),
                    usageContext.getAgentId(),
                    usageContext.getModelId(),
                    usageContext.getFinalResponse(),
                    usageContext.getSkillResults(),
                    usageContext.getTraceId(),
                    usageContext.getTokenUsage(),
                    currentTraceparent()
                );

            } catch (RuntimeException e) {
                // 异常处理：记录到追踪
                EchoMindTrace.recordException(span, e);
                PipelineContext errorCtx = usageContext == null ? new PipelineContext() : usageContext;
                fillErrorContext(errorCtx, request, span);

                // 治理拒绝异常（如配额超限）
                if (e instanceof ModelInvocationRejectedException rejected) {
                    governanceService.releaseReservations(reservationIds(errorCtx, request));
                    ErrorDetail detail = rejected.errorDetail();
                    if (detail != null) {
                        errorCtx.markGovernanceRejected(detail);
                    }
                    return ChatResponse.error(request.requestId(), governanceErrorMessage(e, detail), detail,
                        errorCtx.getTraceId(), currentTraceparent());
                }

                // 配额或预算超限异常
                if (isQuotaOrProviderBudgetExceeded(e)) {
                    governanceService.releaseReservations(reservationIds(errorCtx, request));
                    return ChatResponse.error(request.requestId(), e.getMessage(), errorDetail(e), errorCtx.getTraceId(),
                        currentTraceparent());
                }

                // 其他异常：记录用量并返回错误响应
                try {
                    governanceService.recordStreamUsage(span, OPERATION, authUser, errorCtx, startedNanos, true, e.getMessage());
                } finally {
                    return ChatResponse.error(request.requestId(), e.getMessage(), errorCtx.getTraceId(),
                        currentTraceparent());
                }
            }
        } finally {
            // 结束追踪 Span
            span.end();
        }
    }

    /**
     * 确保追踪ID存在，如果不存在则从 Span 获取。
     */
    private void ensureTraceId(PipelineContext ctx, Span span) {
        if (ctx.getTraceId() == null || ctx.getTraceId().isBlank()) {
            ctx.setTraceId(EchoMindTrace.traceId(span));
        }
    }

    /**
     * 填充错误上下文信息。
     */
    private void fillErrorContext(PipelineContext ctx, ChatRequest request, Span span) {
        attachRequestReservations(ctx, request);
        ensureTraceId(ctx, span);
        if (ctx.getUserId() == null || ctx.getUserId().isBlank()) {
            ctx.setUserId(request.userId());
        }
        if (ctx.getAgentId() == null || ctx.getAgentId().isBlank()) {
            ctx.setAgentId(request.agentId());
        }
        if (ctx.getSessionId() == null || ctx.getSessionId().isBlank()) {
            ctx.setSessionId(request.sessionId());
        }
    }

    /**
     * 将请求中的预留ID附加到上下文。
     */
    private void attachRequestReservations(PipelineContext ctx, ChatRequest request) {
        if (ctx == null || request == null) {
            return;
        }
        if (!request.userReservationIds().isEmpty()) {
            ctx.getAttributes().putIfAbsent(PipelineContext.ATTR_USER_TOKEN_RESERVATION_IDS,
                request.userReservationIds());
        }
        if (!request.providerReservationIds().isEmpty()) {
            ctx.getAttributes().putIfAbsent(PipelineContext.ATTR_PROVIDER_TOKEN_RESERVATION_IDS,
                request.providerReservationIds());
        }
    }

    /**
     * 构建请求预留属性映射。
     */
    private Map<String, Object> requestReservationAttributes(ChatRequest request) {
        Map<String, Object> attributes = new HashMap<>();
        if (request == null) {
            return attributes;
        }
        if (!request.userReservationIds().isEmpty()) {
            attributes.put(PipelineContext.ATTR_USER_TOKEN_RESERVATION_IDS, request.userReservationIds());
        }
        if (!request.providerReservationIds().isEmpty()) {
            attributes.put(PipelineContext.ATTR_PROVIDER_TOKEN_RESERVATION_IDS, request.providerReservationIds());
        }
        return attributes;
    }

    /**
     * 获取所有预留ID（用户预留 + 提供者预留）。
     */
    @SuppressWarnings("unchecked")
    private List<String> reservationIds(PipelineContext ctx, ChatRequest request) {
        List<String> reservations = new ArrayList<>();
        reservations.addAll(reservationIds(ctx, PipelineContext.ATTR_USER_TOKEN_RESERVATION_IDS,
            request == null ? List.of() : request.userReservationIds()));
        reservations.addAll(reservationIds(ctx, PipelineContext.ATTR_PROVIDER_TOKEN_RESERVATION_IDS,
            request == null ? List.of() : request.providerReservationIds()));
        return reservations;
    }

    /**
     * 从上下文获取预留ID列表，如果上下文为空则使用后备值。
     */
    private List<String> reservationIds(PipelineContext ctx, String attribute, List<String> fallback) {
        Object value = ctx == null ? null : ctx.getAttributes().get(attribute);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return fallback == null ? List.of() : fallback;
    }

    /**
     * 标记请求属性到追踪 Span。
     */
    private void tagRequest(Span span, ChatRequest request) {
        span.setAttribute("echomind.request_id", safe(request.requestId()));
        span.setAttribute("echomind.trace.parent_id", safe(request.traceId()));
        span.setAttribute("echomind.user_id", safe(request.userId()));
        span.setAttribute("echomind.agent_id", safe(request.agentId()));
        span.setAttribute("echomind.session_id", safe(request.sessionId()));
    }

    /**
     * 发送事件到回调。
     */
    private void emit(Consumer<ChatStreamEvent> events, ChatStreamEvent event) {
        events.accept(event.withTrace(EchoMindTrace.currentTraceId(), currentTraceparent()));
    }

    /**
     * 获取当前追踪的 traceparent 头。
     */
    private String currentTraceparent() {
        return EchoMindTrace.injectContext().get("traceparent");
    }

    /**
     * 构建追踪载体（用于传递追踪上下文）。
     */
    private Map<String, String> traceCarrier(ChatRequest request) {
        Map<String, String> carrier = new HashMap<>();
        if (request.traceparent() != null && !request.traceparent().isBlank()) {
            carrier.put("traceparent", request.traceparent());
        }
        return carrier;
    }

    /**
     * 构建队列认证用户。
     */
    private AuthUser queuedAuthUser(ChatRequest request) {
        String userId = request.userId() == null || request.userId().isBlank()
            ? AuthUser.DEFAULT_USER_ID
            : request.userId();
        return new AuthUser(userId, userId, !AuthUser.DEFAULT_USER_ID.equals(userId));
    }

    /**
     * 判断异常是否为配额或预算超限异常。
     */
    private boolean isQuotaOrProviderBudgetExceeded(RuntimeException e) {
        return e instanceof ProviderTokenBudgetExceededException || e instanceof TokenQuotaExceededException;
    }

    /**
     * 获取治理错误消息。
     */
    private String governanceErrorMessage(RuntimeException e, ErrorDetail detail) {
        if (detail != null && detail.message() != null && !detail.message().isBlank()) {
            return detail.message();
        }
        return e.getMessage();
    }

    /**
     * 根据异常类型构建错误详情。
     */
    private ErrorDetail errorDetail(RuntimeException e) {
        if (e instanceof TokenQuotaExceededException quota) {
            return new ErrorDetail(
                "USER_TOKEN_QUOTA_EXCEEDED",
                "Token " + TokenQuotaExceededException.scopeLabel(quota.scope()) + "额度已超限（已用 " + quota.usedTokens()
                    + " / 限额 " + quota.limitTokens() + "）",
                "INITIAL_RESERVATION",
                quota.scope(),
                quota.limitTokens(),
                quota.usedTokens(),
                null,
                null,
                Math.max(0, quota.limitTokens() - quota.usedTokens()),
                null
            );
        }
        if (e instanceof ProviderTokenBudgetExceededException budget) {
            return new ErrorDetail(
                "PROVIDER_TOKEN_BUDGET_EXCEEDED",
                "模型服务预算不足，" + ProviderTokenBudgetExceededException.scopeLabel(budget.scope()) + "预算已耗尽"
                    + "（已用 " + budget.usedTokens() + " / 限额 " + budget.limitTokens() + "），请稍后重试或切换模型",
                "INITIAL_RESERVATION",
                budget.scope(),
                budget.limitTokens(),
                budget.usedTokens(),
                null,
                null,
                Math.max(0, budget.limitTokens() - budget.usedTokens()),
                budget.providerId()
            );
        }
        return null;
    }

    /**
     * 安全获取字符串，null 返回空串。
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }
}
