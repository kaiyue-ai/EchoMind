package com.echomind.console.service;

import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatResponse;
import com.echomind.common.model.ChatStreamEvent;
import com.echomind.common.observability.EchoMindTrace;
import com.echomind.console.auth.AuthContext;
import com.echomind.console.dto.ChatMessageRequest;
import com.echomind.console.dto.ChatSubmitResponse;
import com.echomind.console.reservation.TokenEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.List;

/**
 * 聊天应用服务。
 *
 * <p>统一收口异步聊天的应用层逻辑；Controller 只处理 HTTP/SSE 边界，
 * RabbitMQ Consumer 只负责消息出入队。公开聊天入口必须走 RabbitMQ 队列，避免同步
 * 直连 Agent 管线绕开削峰和 SSE 事件链路。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatApplicationService {

    private final ChatRabbitProducer rabbitProducer;
    private final SsePushService ssePushService;
    private final ChatGovernanceService governanceService;
    private final QueuedChatStreamExecutor queuedChatStreamExecutor;
    private final ChatSessionCleanupService sessionCleanupService;
    private final ChatProviderResolver providerResolver;

    private final ChatRequestNormalizer requestNormalizer = new ChatRequestNormalizer();

    /**
     * 提交异步聊天请求，立即返回 requestId 和 sessionId。
     *
     * <p>核心流程：请求标准化 → 创建追踪Span → 应用治理规则 → 发布到RabbitMQ异步队列。
     * 客户端通过requestId订阅SSE推送获取结果。
     *
     * @param request 聊天消息请求
     * @return 包含requestId、sessionId、traceId的响应
     */
    public ChatSubmitResponse submitAsync(ChatMessageRequest request) {
        // 1. 请求标准化：统一格式、验证、默认值填充
        NormalizedChatRequest normalized = requestNormalizer.normalize(request);
        log.info("submitAsync: {}", normalized);

        // 2. 创建追踪Span：标记请求入口，记录链路信息
        Span span = chatSpan("echomind.chat.submit", normalized);

        try (Scope ignored = span.makeCurrent()) {
            // 3. 应用治理规则：限流、配额检查、权限验证
            ChatGovernanceService.RequestInspection requestInspection =
                governanceService.inspectRequest(span, normalized.authUser(), normalized.agentId(),
                    normalized.sessionId(), normalized.message());

            // 4. 获取TraceID：用于全链路追踪关联
            String traceId = EchoMindTrace.traceId(span);

            // 5. 生成请求ID：唯一标识本次请求，用于SSE推送关联
            String requestId = UUID.randomUUID().toString();

            if (requestInspection.shortCircuited()) {
                // 6. 注册SSE请求：建立客户端连接关联(将结果推送给指定的用户的的请求)
                ssePushService.registerRequest(requestId, normalized.userId());
                ssePushService.pushEvent(ChatStreamEvent.result(ChatResponse.success(
                    requestId,
                    normalized.sessionId(),
                    normalized.agentId(),
                    normalized.modelId(),
                    requestInspection.shortCircuitReply(),
                    List.of(),
                    traceId,
                    null,
                    EchoMindTrace.injectContext().get("traceparent")
                )));
                span.setAttribute("echomind.request_id", requestId);
                return new ChatSubmitResponse(requestId, normalized.sessionId(), traceId);
            }

            normalized = normalized.withMessage(requestInspection.governedMessage());

            List<String> userReservationIds = List.of();
            List<String> providerReservationIds = List.of();
            boolean sseRegistered = false;
            try {
                long estimatedTokens = TokenEstimator.estimate(normalized.message());
                userReservationIds = safeReservations(
                    governanceService.reserveUserQuota(normalized.authUser(), requestId, estimatedTokens));
                providerReservationIds = providerResolver.resolveProviderId(normalized.agentId(), normalized.modelId())
                    .map(providerId -> safeReservations(
                        governanceService.reserveProviderBudget(providerId, requestId, estimatedTokens)))
                    .orElse(List.of());

                // 7. 注册SSE请求：只有可执行请求才进入在线流式请求索引。
                ssePushService.registerRequest(requestId, normalized.userId());
                sseRegistered = true;

                // 7. 发布消息到RabbitMQ：异步处理核心，解耦请求提交与处理
                rabbitProducer.publish(new ChatRequest(
                    requestId,           // 请求唯一标识
                    normalized.userId(), // 用户ID
                    normalized.agentId(), // Agent ID
                    normalized.sessionId(), // 会话ID
                    normalized.message(),  // 用户消息
                    normalized.modelId(),  // 模型ID
                    traceId,              // 追踪ID
                    EchoMindTrace.injectContext().get("traceparent"), // 追踪上下文（用于跨服务追踪）
                    normalized.attachments(), // 附件
                    userReservationIds,
                    providerReservationIds
                ));
            } catch (RuntimeException e) {
                // 发布失败：清理SSE注册，避免无效连接泄漏
                if (sseRegistered) {
                    ssePushService.discardRequest(requestId);
                }
                governanceService.releaseReservations(userReservationIds);
                governanceService.releaseReservations(providerReservationIds);
                throw e;
            }

            // 8. 记录请求ID到Span：追踪链路上标记请求标识
            span.setAttribute("echomind.request_id", requestId);

            // 9. 返回响应：立即返回，不等待实际处理完成
            return new ChatSubmitResponse(requestId, normalized.sessionId(), traceId);
        } catch (RuntimeException e) {
            // 记录异常到追踪系统，便于问题排查
            EchoMindTrace.recordException(span, e);
            throw e;
        } finally {
            // 结束Span：完成追踪记录
            span.end();
        }
    }

    /** 执行队列流式聊天，通过RabbitMQ异步处理。 */
    public ChatResponse executeQueuedStream(ChatRequest request, Consumer<ChatStreamEvent> eventConsumer) {
        return queuedChatStreamExecutor.execute(request, eventConsumer);
    }

    /** 清理会话历史。 */
    public Map<String, String> deleteSession(String sessionId) {
        return sessionCleanupService.deleteSession(AuthContext.userId(), sessionId);
    }

    /** 创建聊天追踪Span。 */
    private Span chatSpan(String name, NormalizedChatRequest n) {
        Span span = EchoMindTrace.startSpan(name);
        span.setAttribute("echomind.user_id", safe(n.userId()));
        span.setAttribute("echomind.agent_id", safe(n.agentId()));
        span.setAttribute("echomind.session_id", safe(n.sessionId()));
        return span;
    }

    /** 安全获取字符串，避免空指针。 */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    private List<String> safeReservations(List<String> reservationIds) {
        return reservationIds == null ? List.of() : reservationIds;
    }
}
