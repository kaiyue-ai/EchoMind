package com.echomind.console.service;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatResponse;
import com.echomind.common.model.ChatStreamEvent;
import com.echomind.common.observability.EchoMindTrace;
import com.echomind.console.auth.AuthContext;
import com.echomind.console.dto.ChatMessageRequest;
import com.echomind.console.dto.ChatSubmitResponse;
import com.echomind.console.dto.ChatSyncResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 聊天应用服务。
 *
 * <p>统一收口同步、异步和流式聊天的应用层逻辑；Controller 只处理 HTTP/SSE 边界，
 * RabbitMQ Consumer 只负责消息出入队。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatApplicationService {

    private final AgentChatExecutor agentChatExecutor;
    private final ChatRabbitProducer rabbitProducer;
    private final SsePushService ssePushService;
    private final ChatGovernanceService governanceService;
    private final QueuedChatStreamExecutor queuedChatStreamExecutor;
    private final ChatSessionCleanupService sessionCleanupService;

    private final ChatRequestNormalizer requestNormalizer = new ChatRequestNormalizer();

    /** 提交异步聊天请求，立即返回 requestId 和 sessionId。 */
    public ChatSubmitResponse submitAsync(ChatMessageRequest request) {
        NormalizedChatRequest normalized = requestNormalizer.normalize(request);
        Span span = chatSpan("echomind.chat.submit", normalized);
        try (Scope ignored = span.makeCurrent()) {
            normalized = applyRequestGovernance(span, normalized);
            String traceId = EchoMindTrace.traceId(span);
            String requestId = UUID.randomUUID().toString();
            ssePushService.registerRequest(requestId, normalized.userId());
            try {
                rabbitProducer.publish(new ChatRequest(
                    requestId,
                    normalized.userId(),
                    normalized.agentId(),
                    normalized.sessionId(),
                    normalized.message(),
                    normalized.modelId(),
                    traceId,
                    EchoMindTrace.injectContext().get("traceparent"),
                    normalized.attachments()
                ));
            } catch (RuntimeException e) {
                ssePushService.discardRequest(requestId);
                throw e;
            }
            span.setAttribute("echomind.request_id", requestId);
            return new ChatSubmitResponse(requestId, normalized.sessionId(), traceId);
        } catch (RuntimeException e) {
            EchoMindTrace.recordException(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** 执行同步聊天，直接返回完整结果。 */
    public ChatSyncResponse executeSync(ChatMessageRequest request) {
        NormalizedChatRequest n = requestNormalizer.normalize(request);
        Span span = chatSpan("echomind.chat.sync", n);
        long startedNanos = System.nanoTime();
        PipelineContext ctx = null;
        boolean callErrorEmitted = false;
        try (Scope ignored = span.makeCurrent()) {
            String rawMessage = n.message();
            n = applyRequestGovernance(span, n);
            ctx = agentChatExecutor.execute(n.userId(), n.agentId(), n.sessionId(), n.message(), n.modelId(),
                n.attachments(), rawMessage);
            if (ctx.getTraceId() == null || ctx.getTraceId().isBlank()) {
                ctx.setTraceId(EchoMindTrace.traceId(span));
            }
            callErrorEmitted = governanceService.emitCallErrorIfFailed(n.authUser(), ctx);
            governanceService.inspectResponse(n.authUser(), ctx);
            governanceService.recordSuccessAndWarnings(span, "echomind.chat.sync", n.authUser(), ctx, startedNanos);
            return ChatSyncResponse.from(ctx);
        } catch (RuntimeException e) {
            EchoMindTrace.recordException(span, e);
            if (ctx != null) {
                governanceService.recordErrorQuietly(span, "echomind.chat.sync", n.authUser(), ctx, startedNanos, e.getMessage());
                if (!callErrorEmitted) {
                    governanceService.emitCallError(n.authUser(), ctx, e.getMessage());
                }
            }
            throw e;
        } finally {
            span.end();
        }
    }

    /** 执行队列中的聊天请求，并把 token 事件发布给 SSE 网关。 */
    public ChatResponse executeQueuedStream(ChatRequest request, Consumer<ChatStreamEvent> events) {
        return queuedChatStreamExecutor.execute(request, events);
    }

    /** 删除单条普通聊天会话，并尽力回收历史消息中的附件对象。 */
    public Map<String, String> deleteSession(String sessionId) {
        return sessionCleanupService.deleteSession(AuthContext.userId(), sessionId);
    }

    private Span chatSpan(String spanName, NormalizedChatRequest request) {
        Span span = EchoMindTrace.startSpan(spanName);
        span.setAttribute("echomind.user_id", safe(request.userId()));
        span.setAttribute("echomind.username", safe(request.authUser().username()));
        span.setAttribute("echomind.account_type", "client");
        span.setAttribute("echomind.agent_id", safe(request.agentId()));
        span.setAttribute("echomind.session_id", safe(request.sessionId()));
        span.setAttribute("echomind.model_id", safe(request.modelId()));
        span.setAttribute("echomind.attachment_count", request.attachments() == null ? 0 : request.attachments().size());
        return span;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private NormalizedChatRequest applyRequestGovernance(Span span, NormalizedChatRequest request) {
        String governedMessage = governanceService.inspectRequest(span, request.authUser(), request.agentId(),
            request.sessionId(), request.message());
        return request.withMessage(governedMessage);
    }
}
