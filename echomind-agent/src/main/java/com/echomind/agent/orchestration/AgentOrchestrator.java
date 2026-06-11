package com.echomind.agent.orchestration;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.MessageAttachment;
import com.echomind.common.observability.EchoMindTrace;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 编排入口。
 *
 * <p>负责选择 Agent、补齐会话上下文，然后把请求交给 Agent 管线。找不到指定
 * agentId 时会回退到第一个可用 Agent。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final AgentFactory agentFactory;

    /** 执行一次完整对话，返回包含最终响应和中间结果的上下文。 */
    public PipelineContext execute(String agentId, String sessionId, String userMessage) {
        return execute(agentId, sessionId, userMessage, null);
    }

    /** 执行一次完整对话，可指定覆盖 Agent 默认模型的 modelId。 */
    public PipelineContext execute(String agentId, String sessionId, String userMessage, String modelId) {
        return execute(agentId, sessionId, userMessage, modelId, List.of());
    }

    /** 执行一次完整对话，可携带图片等多模态附件。 */
    public PipelineContext execute(String agentId, String sessionId, String userMessage, String modelId,
                                   List<MessageAttachment> attachments) {
        return execute(agentId, sessionId, userMessage, modelId, attachments, true);
    }

    /** 执行一次完整对话，可携带用户身份。 */
    public PipelineContext execute(String userId, String agentId, String sessionId, String userMessage, String modelId,
                                   List<MessageAttachment> attachments) {
        return execute(userId, agentId, sessionId, userMessage, modelId, attachments, true);
    }

    /** 执行一次完整对话，同时保留治理前原文供模型工具调用和审计使用。 */
    public PipelineContext execute(String userId, String agentId, String sessionId, String userMessage, String modelId,
                                   List<MessageAttachment> attachments, String rawUserMessage) {
        return execute(userId, agentId, sessionId, userMessage, modelId, attachments, true, rawUserMessage);
    }

    /** 执行内部任务，不读取或写入普通聊天记忆。 */
    public PipelineContext executeInternal(String agentId, String sessionId, String userMessage) {
        return executeInternal("default", agentId, sessionId, userMessage, true);
    }

    /** 执行内部任务，并可按调用方控制是否暴露 Agent 工具。 */
    public PipelineContext executeInternal(String agentId, String sessionId, String userMessage,
                                           boolean toolExposureEnabled) {
        return executeInternal("default", agentId, sessionId, userMessage, toolExposureEnabled);
    }

    /** 执行内部任务，并允许调用方在 PipelineContext 创建时写入初始属性。 */
    public PipelineContext executeInternal(String agentId, String sessionId, String userMessage,
                                           boolean toolExposureEnabled, Map<String, Object> initialAttributes) {
        return executeInternal("default", agentId, sessionId, userMessage, toolExposureEnabled, initialAttributes);
    }

    /** 执行内部任务，可指定用量归属用户，但仍不写普通聊天记忆。 */
    public PipelineContext executeInternal(String userId, String agentId, String sessionId, String userMessage,
                                           boolean toolExposureEnabled) {
        return execute(userId, agentId, sessionId, userMessage, null, List.of(), false, toolExposureEnabled,
            null);
    }

    /** 执行内部任务，可指定用量归属用户和初始 PipelineContext 属性。 */
    public PipelineContext executeInternal(String userId, String agentId, String sessionId, String userMessage,
                                           boolean toolExposureEnabled, Map<String, Object> initialAttributes) {
        return execute(userId, agentId, sessionId, userMessage, null, List.of(), false, toolExposureEnabled,
            null, initialAttributes);
    }

    private Agent resolveAgent(String agentId) {
        Agent agent = agentFactory.get(agentId);
        if (agent == null) {
            log.warn("Agent not found: {}, using first available", agentId);
            var agents = agentFactory.allAgents();
            if (agents.isEmpty()) {
                return null;
            }
            agent = agents.values().iterator().next();
        }
        return agent;
    }

    private PipelineContext buildPipelineContext(String userId, Agent agent, String sessionId, String userMessage,
                                                  String modelId, List<MessageAttachment> attachments) {
        return buildPipelineContext(userId, agent, sessionId, userMessage, modelId, attachments, null);
    }

    private PipelineContext buildPipelineContext(String userId, Agent agent, String sessionId, String userMessage,
                                                  String modelId, List<MessageAttachment> attachments,
                                                  String rawUserMessage) {
        return buildPipelineContext(userId, agent, sessionId, userMessage, modelId, attachments, rawUserMessage,
            Map.of());
    }

    private PipelineContext buildPipelineContext(String userId, Agent agent, String sessionId, String userMessage,
                                                  String modelId, List<MessageAttachment> attachments,
                                                  String rawUserMessage, Map<String, Object> initialAttributes) {
        PipelineContext ctx = new PipelineContext();
        ctx.setUserId(normalizeUserId(userId));
        ctx.setAgentId(agent.getAgentId());
        ctx.setSessionId(sessionId != null ? sessionId : UUID.randomUUID().toString());
        ctx.setUserMessage(userMessage);
        ctx.setSystemPrompt(agent.getSystemPrompt());
        ctx.setModelId(modelId != null && !modelId.isBlank() ? modelId : agent.getDefaultModelId());
        ctx.setTraceId(EchoMindTrace.currentTraceId());
        if (attachments != null) {
            ctx.getAttachments().addAll(attachments);
        }
        if (rawUserMessage != null && !rawUserMessage.isBlank()) {
            ctx.getAttributes().put(PipelineContext.ATTR_RAW_USER_MESSAGE, rawUserMessage);
        }
        if (initialAttributes != null && !initialAttributes.isEmpty()) {
            ctx.getAttributes().putAll(initialAttributes);
        }
        return ctx;
    }

    private PipelineContext execute(String agentId, String sessionId, String userMessage, String modelId,
                                    List<MessageAttachment> attachments, boolean memoryPersistenceEnabled) {
        return execute("default", agentId, sessionId, userMessage, modelId, attachments, memoryPersistenceEnabled,
            true, null);
    }

    private PipelineContext execute(String userId, String agentId, String sessionId, String userMessage, String modelId,
                                    List<MessageAttachment> attachments, boolean memoryPersistenceEnabled) {
        return execute(userId, agentId, sessionId, userMessage, modelId, attachments, memoryPersistenceEnabled, true,
            null);
    }

    private PipelineContext execute(String userId, String agentId, String sessionId, String userMessage, String modelId,
                                    List<MessageAttachment> attachments, boolean memoryPersistenceEnabled,
                                    String rawUserMessage) {
        return execute(userId, agentId, sessionId, userMessage, modelId, attachments, memoryPersistenceEnabled, true,
            rawUserMessage);
    }

    /**
     * 执行 Agent 对话的核心方法
     *
     * @param userId 用户ID
     * @param agentId Agent ID
     * @param sessionId 会话ID
     * @param userMessage 用户消息
     * @param modelId 模型ID
     * @param attachments 消息附件
     * @param memoryPersistenceEnabled 是否启用记忆持久化
     * @param toolExposureEnabled 是否启用工具暴露
     * @param rawUserMessage 原始用户消息（未处理）
     * @return PipelineContext 包含执行结果的上下文
     */
    private PipelineContext execute(String userId, String agentId, String sessionId, String userMessage, String modelId,
                                    List<MessageAttachment> attachments, boolean memoryPersistenceEnabled,
                                    boolean toolExposureEnabled, String rawUserMessage) {
        return execute(userId, agentId, sessionId, userMessage, modelId, attachments, memoryPersistenceEnabled,
            toolExposureEnabled, rawUserMessage, Map.of());
    }

    private PipelineContext execute(String userId, String agentId, String sessionId, String userMessage, String modelId,
                                    List<MessageAttachment> attachments, boolean memoryPersistenceEnabled,
                                    boolean toolExposureEnabled, String rawUserMessage,
                                    Map<String, Object> initialAttributes) {
        // 1. 解析并获取 Agent 实例
        Agent agent = resolveAgent(agentId);
        if (agent == null) {
            // 如果没有配置 Agent，返回失败上下文
            PipelineContext ctx = new PipelineContext();
            ctx.setUserId(normalizeUserId(userId));
            ctx.setTraceId(EchoMindTrace.currentTraceId());
            ctx.markFailed("No agents configured");
            return ctx;
        }

        // 2. 构建执行上下文
        PipelineContext ctx = buildPipelineContext(userId, agent, sessionId, userMessage, modelId, attachments,
            rawUserMessage, initialAttributes);
        // 设置记忆持久化标志
        ctx.setMemoryPersistenceEnabled(memoryPersistenceEnabled);

        // 3. 如果禁用工具暴露，设置属性标记
        if (!toolExposureEnabled) {
            ctx.getAttributes().put(PipelineContext.ATTR_TOOL_EXPOSURE_DISABLED, true);
        }

        // 4. 记录日志（截断消息到50字符）
        log.info("[Orchestrator] Agent={} model={} session={} msg={}", agent.getAgentId(),
            ctx.getModelId(), ctx.getSessionId(),
            userMessage.substring(0, Math.min(50, userMessage.length())));

        // 5. 创建追踪 Span，执行 Agent 对话
        Span span = orchestrationSpan("echomind.agent.orchestrate", ctx);
        try (Scope ignored = span.makeCurrent()) {
            // 设置追踪ID到上下文
            ctx.setTraceId(EchoMindTrace.traceId(span));
            // 调用 Agent 的 chat 方法执行对话
            return agent.chat(ctx);
        } catch (RuntimeException e) {
            // 记录异常到追踪
            EchoMindTrace.recordException(span, e);
            throw e;
        } finally {
            // 结束追踪 Span
            span.end();
        }
    }

    /** 执行流式对话，并在结束时保留 PipelineContext 给调用层读取原生 token usage。 */
    public Mono<StreamExecution> executeStreamContext(String userId, String agentId, String sessionId,
                                                     String userMessage, String modelId,
                                                     List<MessageAttachment> attachments) {
        return executeStreamContext(userId, agentId, sessionId, userMessage, modelId, attachments, null);
    }

    public Mono<StreamExecution> executeStreamContext(String userId, String agentId, String sessionId,
                                                     String userMessage, String modelId,
                                                     List<MessageAttachment> attachments,
                                                     String rawUserMessage) {
        return executeStreamContext(userId, agentId, sessionId, userMessage, modelId, attachments, rawUserMessage,
            Map.of());
    }

    /**
     * 执行流式对话，并在结束时保留 PipelineContext 给调用层读取原生 token usage。
     *
     * <p>核心流程：
     * 1. 解析 Agent（不存在则返回失败上下文）
     * 2. 构建 PipelineContext
     * 3. 创建追踪 Span
     * 4. 调用 Agent.chatStream() 获取 token 流
     * 5. 返回 StreamExecution（包含上下文和流）
     *
     * @param userId 用户ID
     * @param agentId Agent ID
     * @param sessionId 会话ID
     * @param userMessage 用户消息
     * @param modelId 模型ID（可选，覆盖Agent默认模型）
     * @param attachments 消息附件（图片等）
     * @param rawUserMessage 原始用户消息（用于审计）
     * @param initialAttributes 初始上下文属性
     * @return Mono<StreamExecution> 流式执行结果
     */
    public Mono<StreamExecution> executeStreamContext(String userId, String agentId, String sessionId,
                                                     String userMessage, String modelId,
                                                     List<MessageAttachment> attachments,
                                                     String rawUserMessage,
                                                     Map<String, Object> initialAttributes) {
        // 使用 Mono.defer() 延迟执行，只在订阅时才创建上下文和调用Agent
        return Mono.defer(() -> {
            // 1. 解析 Agent（不存在则回退到第一个可用Agent，仍不存在则返回失败）
            Agent agent = resolveAgent(agentId);
            if (agent == null) {
                // Agent 不存在，构建失败上下文
                PipelineContext missing = new PipelineContext();
                missing.setUserId(normalizeUserId(userId));
                missing.setAgentId(agentId);
                missing.setSessionId(sessionId);
                missing.setUserMessage(userMessage);
                missing.setModelId(modelId);
                missing.setTraceId(EchoMindTrace.currentTraceId());
                missing.markFailed("No agents configured");
                return Mono.just(new StreamExecution(missing, Flux.just(missing.getFinalResponse())));
            }

            // 2. 构建执行上下文
            PipelineContext ctx = buildPipelineContext(userId, agent, sessionId, userMessage, modelId, attachments,
                rawUserMessage, initialAttributes);

            // 3. 记录日志（截断消息到50字符）
            log.info("[Orchestrator:stream] Agent={} model={} session={} msg={}", agent.getAgentId(),
                ctx.getModelId(), ctx.getSessionId(),
                userMessage.substring(0, Math.min(50, userMessage.length())));

            // 4. 创建 OpenTelemetry 追踪 Span
            Span span = orchestrationSpan("echomind.agent.orchestrate_stream", ctx);
            try (Scope ignored = span.makeCurrent()) {
                // 设置追踪ID到上下文
                ctx.setTraceId(EchoMindTrace.traceId(span));
                
                // 5. 调用 Agent 的流式对话方法
                Flux<String> tokens = agent.chatStream(ctx)
                    // 错误时记录异常到追踪
                    .doOnError(error -> EchoMindTrace.recordException(span, error))
                    // 流结束时结束追踪Span
                    .doFinally(signalType -> span.end());
                
                // 6. 返回 StreamExecution（包含上下文和token流）
                return Mono.just(new StreamExecution(ctx, tokens));
            } catch (RuntimeException e) {
                // 同步异常处理：记录到追踪并重新抛出
                EchoMindTrace.recordException(span, e);
                span.end();
                throw e;
            }
        });
    }

    public record StreamExecution(PipelineContext context, Flux<String> tokens) {
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    private Span orchestrationSpan(String name, PipelineContext ctx) {
        Span span = EchoMindTrace.startSpan(name);
        span.setAttribute("echomind.user_id", safe(ctx.getUserId()));
        span.setAttribute("echomind.agent_id", safe(ctx.getAgentId()));
        span.setAttribute("echomind.session_id", safe(ctx.getSessionId()));
        span.setAttribute("echomind.model_id", safe(ctx.getModelId()));
        span.setAttribute("echomind.memory_enabled", ctx.isMemoryPersistenceEnabled());
        return span;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
