package com.echomind.console.service;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatResponse;
import com.echomind.common.model.ChatStreamEvent;
import com.echomind.common.observability.EchoMindTrace;
import com.echomind.console.auth.AuthContext;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.budget.ProviderTokenBudgetExceededException;
import com.echomind.console.dto.ChatMessageRequest;
import com.echomind.console.dto.ChatSubmitResponse;
import com.echomind.console.dto.ChatSyncResponse;
import com.echomind.console.quota.TokenQuotaExceededException;
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
            normalized = applyRequestGovernance(span, normalized);

            // 4. 获取TraceID：用于全链路追踪关联
            String traceId = EchoMindTrace.traceId(span);

            // 5. 生成请求ID：唯一标识本次请求，用于SSE推送关联
            String requestId = UUID.randomUUID().toString();

            // 6. 注册SSE请求：建立客户端连接关联(将结果推送给指定的用户的的请求)
            ssePushService.registerRequest(requestId, normalized.userId());

            try {
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
                    normalized.attachments() // 附件
                ));
            } catch (RuntimeException e) {
                // 发布失败：清理SSE注册，避免无效连接泄漏
                ssePushService.discardRequest(requestId);
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

    /**
     * 执行同步聊天，直接返回完整结果。
     *
     * <p>核心流程：请求标准化 → 创建追踪Span → 应用治理规则 → 执行Agent管线 → 处理响应 → 记录使用量。
     * 适用于需要立即获取完整回复的场景，如API调用、测试等。
     *
     * @param request 聊天消息请求
     * @return 包含最终回复、会话信息和追踪ID的响应
     */
    public ChatSyncResponse executeSync(ChatMessageRequest request) {
        // 1. 请求标准化：统一格式、验证、默认值填充
        NormalizedChatRequest n = requestNormalizer.normalize(request);

        // 2. 创建追踪Span：标记请求入口，记录链路信息（用户ID、Agent ID、会话ID）
        Span span = chatSpan("echomind.chat.sync", n);

        // 3. 记录开始时间：用于计算调用耗时
        long startedNanos = System.nanoTime();

        // 4. 初始化上下文和错误标记
        PipelineContext ctx = null;
        boolean callErrorEmitted = false;

        try (Scope ignored = span.makeCurrent()) {
            // 5. 保存原始消息（用于敏感数据检测）
            String rawMessage = n.message();

            // 6. 应用治理规则：限流、配额检查、权限验证
            n = applyRequestGovernance(span, n);

            // 7. 执行Agent管线：核心处理逻辑（上下文加载、模型选择、工具调用、LLM推理）
            ctx = agentChatExecutor.execute(n.userId(), n.agentId(), n.sessionId(), n.message(), n.modelId(),
                n.attachments(), rawMessage);

            // 8. 设置追踪ID：确保上下文中有有效的TraceID用于全链路追踪
            if (ctx.getTraceId() == null || ctx.getTraceId().isBlank()) {
                ctx.setTraceId(EchoMindTrace.traceId(span));
            }

            // 9. 错误检测与告警：如果Agent执行失败，触发告警通知
            callErrorEmitted = governanceService.emitCallErrorIfFailed(n.authUser(), ctx);

            // 10. 响应检查：检测响应中的敏感数据并进行脱敏处理
            governanceService.inspectResponse(n.authUser(), ctx);

            // 11. 记录使用量：持久化Token使用记录到数据库，并检查配额警告
            governanceService.recordSuccessAndWarnings(span, "echomind.chat.sync", n.authUser(), ctx, startedNanos);

            // 12. 返回响应：将PipelineContext转换为ChatSyncResponse返回给客户端
            return ChatSyncResponse.from(ctx);

        } catch (RuntimeException e) {
            // 异常处理：记录异常到追踪系统，触发告警（如果尚未触发）
            EchoMindTrace.recordException(span, e);
            if (ctx != null) {
                governanceService.inspectResponse(n.authUser(), ctx);
            }
            if (!callErrorEmitted && !isQuotaOrProviderBudgetExceeded(e)) {
                PipelineContext errorCtx = ctx == null ? new PipelineContext() : ctx;
                fillErrorContext(errorCtx, n, span);
                governanceService.emitCallError(n.authUser(), errorCtx, e.getMessage());
            }
            throw e;
        } finally {
            // 结束Span：完成追踪记录，确保追踪数据被正确导出
            span.end();
        }
    }

    /** 执行流式聊天，通过SSE推送结果。 */
    public void executeStream(ChatMessageRequest request, Consumer<ChatStreamEvent> eventConsumer) {
        NormalizedChatRequest n = requestNormalizer.normalize(request);
        Span span = chatSpan("echomind.chat.stream", n);
        try (Scope ignored = span.makeCurrent()) {
            String rawMessage = n.message();
            n = applyRequestGovernance(span, n);
            agentChatExecutor.executeStream(n.userId(), n.agentId(), n.sessionId(), n.message(), n.modelId(),
                n.attachments(), rawMessage);
        } catch (RuntimeException e) {
            EchoMindTrace.recordException(span, e);
            throw e;
        } finally {
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

    /** 应用请求治理规则。 */
    private NormalizedChatRequest applyRequestGovernance(Span span, NormalizedChatRequest n) {
        String governedMessage = governanceService.inspectRequest(span, n.authUser(), n.agentId(), n.sessionId(),
            n.message());
        return n.withMessage(governedMessage);
    }

    private void fillErrorContext(PipelineContext ctx, NormalizedChatRequest request, Span span) {
        if (ctx.getTraceId() == null || ctx.getTraceId().isBlank()) {
            ctx.setTraceId(EchoMindTrace.traceId(span));
        }
        ctx.setUserId(nonBlank(ctx.getUserId(), request.userId()));
        ctx.setAgentId(nonBlank(ctx.getAgentId(), request.agentId()));
        ctx.setSessionId(nonBlank(ctx.getSessionId(), request.sessionId()));
        ctx.setModelId(nonBlank(ctx.getModelId(), request.modelId()));
    }

    private boolean isQuotaOrProviderBudgetExceeded(RuntimeException e) {
        return e instanceof ProviderTokenBudgetExceededException || e instanceof TokenQuotaExceededException;
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

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
