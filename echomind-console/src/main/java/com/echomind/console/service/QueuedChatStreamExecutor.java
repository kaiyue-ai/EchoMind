package com.echomind.console.service;

import com.echomind.agent.orchestration.AgentOrchestrator.StreamExecution;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatResponse;
import com.echomind.common.model.ChatStreamEvent;
import com.echomind.common.observability.EchoMindTrace;
import com.echomind.console.auth.AuthUser;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Executes queued streaming chat requests and publishes token events.
 */
@Service
@RequiredArgsConstructor
class QueuedChatStreamExecutor {

    private static final String OPERATION = "echomind.chat.stream.consume";

    private final AgentChatExecutor agentChatExecutor;
    private final ChatGovernanceService governanceService;

    ChatResponse execute(ChatRequest request, Consumer<ChatStreamEvent> events) {
        Span span = EchoMindTrace.startSpan(OPERATION, EchoMindTrace.extractContext(traceCarrier(request)));
        long startedNanos = System.nanoTime();
        PipelineContext usageContext = null;
        AuthUser authUser = queuedAuthUser(request);
        tagRequest(span, request);
        StreamExecution execution;
        StringBuilder output = new StringBuilder();
        try {
            String governedMessage;
            String rawMessage = request.message();
            try (Scope ignored = span.makeCurrent()) {
                governedMessage = governanceService.inspectRequest(span, authUser, request.agentId(),
                    request.sessionId(), request.message());
            }
            execution = agentChatExecutor.executeStream(request.userId(), request.agentId(), request.sessionId(),
                    governedMessage, request.modelId(), request.attachments(), rawMessage)
                .block();
            if (execution == null) {
                throw new IllegalStateException("stream execution was not created");
            }
            usageContext = execution.context();
            ensureTraceId(usageContext, span);
            events.accept(ChatStreamEvent.meta(request.requestId(), usageContext.getSessionId(), usageContext.getTraceId()));
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
                    events.accept(ChatStreamEvent.token(request.requestId(), token));
                })
                .doOnError(error -> EchoMindTrace.recordException(span, error))
                .blockLast();
            usageContext.setFinalResponse(output.toString());
            governanceService.recordStreamUsage(span, OPERATION, authUser, usageContext, startedNanos, false, null);
            return ChatResponse.success(
                request.requestId(),
                usageContext.getSessionId(),
                usageContext.getAgentId(),
                usageContext.getModelId(),
                usageContext.getFinalResponse(),
                usageContext.getSkillResults(),
                usageContext.getTraceId(),
                usageContext.getTokenUsage()
            );
        } catch (RuntimeException e) {
            EchoMindTrace.recordException(span, e);
            PipelineContext errorCtx = usageContext == null ? new PipelineContext() : usageContext;
            fillErrorContext(errorCtx, request, span);
            try {
                governanceService.recordStreamUsage(span, OPERATION, authUser, errorCtx, startedNanos, true, e.getMessage());
            } finally {
                return ChatResponse.error(request.requestId(), e.getMessage(), errorCtx.getTraceId());
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

    private void tagRequest(Span span, ChatRequest request) {
        span.setAttribute("echomind.request_id", safe(request.requestId()));
        span.setAttribute("echomind.trace.parent_id", safe(request.traceId()));
        span.setAttribute("echomind.user_id", safe(request.userId()));
        span.setAttribute("echomind.agent_id", safe(request.agentId()));
        span.setAttribute("echomind.session_id", safe(request.sessionId()));
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

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
