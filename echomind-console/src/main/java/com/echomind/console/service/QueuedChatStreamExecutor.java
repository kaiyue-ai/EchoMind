package com.echomind.console.service;

import com.echomind.agent.orchestration.AgentOrchestrator.StreamExecution;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatResponse;
import com.echomind.common.model.ChatStreamEvent;
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
 * Executes queued streaming chat requests and publishes token events.
 * 执行从 RabbitMQ 队列中消费的流式聊天请求 ，并将结果通过事件回调推送给客户端
 */
@Service
@RequiredArgsConstructor
class QueuedChatStreamExecutor {

    private static final String OPERATION = "echomind.chat.stream.consume";

    private final AgentChatExecutor agentChatExecutor;
    private final ChatGovernanceService governanceService;
    // 执行队列流式聊天：将请求ID发送到队列，等待异步处理，将结果推送给指定的用户的的请求
    ChatResponse execute(ChatRequest request, Consumer<ChatStreamEvent> events) {
        // 1. 进行埋点：标记请求入口，记录链路信息
        Span span = EchoMindTrace.startSpan(OPERATION, EchoMindTrace.extractContext(traceCarrier(request)));
        long startedNanos = System.nanoTime(); // 记录请求开始时间
        PipelineContext usageContext = null; // 流式聊天上下文
        AuthUser authUser = queuedAuthUser(request); // 认证用户
        tagRequest(span, request); // 标记请求
        StreamExecution execution; // 流式聊天执行
        StringBuilder output = new StringBuilder(); // 输出缓冲区
        try (Scope ignored = span.makeCurrent()) {
            try {
                String rawMessage = request.message();
                execution = agentChatExecutor.executeStream(request.userId(), request.agentId(), request.sessionId(),
                        request.message(), request.modelId(), request.attachments(), rawMessage,
                        requestReservationAttributes(request))
                    .block();
                if (execution == null) {
                    throw new IllegalStateException("stream execution was not created");
                }
                usageContext = execution.context();
                attachRequestReservations(usageContext, request);
                ensureTraceId(usageContext, span);
                usageContext.setToolProgressSink(event -> {
                    if (PipelineContext.ToolProgressEvent.TYPE_START.equals(event.type())) {
                        emit(events, ChatStreamEvent.toolStart(request.requestId(), event.toolName()));
                    } else if (PipelineContext.ToolProgressEvent.TYPE_END.equals(event.type())) {
                        emit(events, ChatStreamEvent.toolEnd(request.requestId(), event.toolName(), event.durationMs()));
                    }
                });
                emit(events, ChatStreamEvent.meta(request.requestId(), usageContext.getSessionId(), usageContext.getTraceId()));
                PipelineContext streamContext = usageContext;
                execution.tokens()
                    .map(token -> governanceService.inspectResponseText(
                        authUser,
                        streamContext.getTraceId(),
                        streamContext.getAgentId(),
                        streamContext.getSessionId(),
                        token
                    ))
                    .doOnNext(token -> {
                        output.append(token);
                        emit(events, ChatStreamEvent.token(request.requestId(), token));
                    })
                    .doOnError(error -> EchoMindTrace.recordException(span, error))
                    .blockLast();
                if (!usageContext.hasFailed()) {
                    usageContext.setFinalResponse(output.toString());
                }
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
                EchoMindTrace.recordException(span, e);
                PipelineContext errorCtx = usageContext == null ? new PipelineContext() : usageContext;
                fillErrorContext(errorCtx, request, span);
                if (isQuotaOrProviderBudgetExceeded(e)) {
                    governanceService.releaseReservations(reservationIds(errorCtx, request));
                    return ChatResponse.error(request.requestId(), e.getMessage(), errorCtx.getTraceId(),
                        currentTraceparent());
                }
                try {
                    governanceService.recordStreamUsage(span, OPERATION, authUser, errorCtx, startedNanos, true, e.getMessage());
                } finally {
                    return ChatResponse.error(request.requestId(), e.getMessage(), errorCtx.getTraceId(),
                        currentTraceparent());
                }
            }
        } finally {
            span.end();
        }
    }

    private void ensureTraceId(PipelineContext ctx, Span span) {
        if (ctx.getTraceId() == null || ctx.getTraceId().isBlank()) {
            ctx.setTraceId(EchoMindTrace.traceId(span));
        }
    }

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

    @SuppressWarnings("unchecked")
    private List<String> reservationIds(PipelineContext ctx, ChatRequest request) {
        List<String> reservations = new ArrayList<>();
        reservations.addAll(reservationIds(ctx, PipelineContext.ATTR_USER_TOKEN_RESERVATION_IDS,
            request == null ? List.of() : request.userReservationIds()));
        reservations.addAll(reservationIds(ctx, PipelineContext.ATTR_PROVIDER_TOKEN_RESERVATION_IDS,
            request == null ? List.of() : request.providerReservationIds()));
        return reservations;
    }

    private List<String> reservationIds(PipelineContext ctx, String attribute, List<String> fallback) {
        Object value = ctx == null ? null : ctx.getAttributes().get(attribute);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return fallback == null ? List.of() : fallback;
    }

    private void tagRequest(Span span, ChatRequest request) {
        span.setAttribute("echomind.request_id", safe(request.requestId()));
        span.setAttribute("echomind.trace.parent_id", safe(request.traceId()));
        span.setAttribute("echomind.user_id", safe(request.userId()));
        span.setAttribute("echomind.agent_id", safe(request.agentId()));
        span.setAttribute("echomind.session_id", safe(request.sessionId()));
    }

    private void emit(Consumer<ChatStreamEvent> events, ChatStreamEvent event) {
        events.accept(event.withTrace(EchoMindTrace.currentTraceId(), currentTraceparent()));
    }

    private String currentTraceparent() {
        return EchoMindTrace.injectContext().get("traceparent");
    }

    private Map<String, String> traceCarrier(ChatRequest request) {
        Map<String, String> carrier = new HashMap<>();
        if (request.traceparent() != null && !request.traceparent().isBlank()) {
            carrier.put("traceparent", request.traceparent());
        }
        return carrier;
    }

    private AuthUser queuedAuthUser(ChatRequest request) {
        String userId = request.userId() == null || request.userId().isBlank()
            ? AuthUser.DEFAULT_USER_ID
            : request.userId();
        return new AuthUser(userId, userId, !AuthUser.DEFAULT_USER_ID.equals(userId));
    }

    private boolean isQuotaOrProviderBudgetExceeded(RuntimeException e) {
        return e instanceof ProviderTokenBudgetExceededException || e instanceof TokenQuotaExceededException;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
