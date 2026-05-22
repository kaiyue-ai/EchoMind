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

    /** 执行一次完整对话，同时保留治理前原文供工具参数抽取使用。 */
    public PipelineContext execute(String userId, String agentId, String sessionId, String userMessage, String modelId,
                                   List<MessageAttachment> attachments, String rawUserMessage) {
        return execute(userId, agentId, sessionId, userMessage, modelId, attachments, true, rawUserMessage);
    }

    /** 执行内部任务，不读取或写入普通聊天记忆。 */
    public PipelineContext executeInternal(String agentId, String sessionId, String userMessage) {
        return execute(agentId, sessionId, userMessage, null, List.of(), false);
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
        ctx.getAttributes().put("agentSkillIds", agent.getSkillIds());
        if (rawUserMessage != null && !rawUserMessage.isBlank()) {
            ctx.getAttributes().put("rawUserMessage", rawUserMessage);
        }
        return ctx;
    }

    private PipelineContext execute(String agentId, String sessionId, String userMessage, String modelId,
                                    List<MessageAttachment> attachments, boolean memoryPersistenceEnabled) {
        return execute("default", agentId, sessionId, userMessage, modelId, attachments, memoryPersistenceEnabled);
    }

    private PipelineContext execute(String userId, String agentId, String sessionId, String userMessage, String modelId,
                                    List<MessageAttachment> attachments, boolean memoryPersistenceEnabled) {
        return execute(userId, agentId, sessionId, userMessage, modelId, attachments, memoryPersistenceEnabled, null);
    }

    private PipelineContext execute(String userId, String agentId, String sessionId, String userMessage, String modelId,
                                    List<MessageAttachment> attachments, boolean memoryPersistenceEnabled,
                                    String rawUserMessage) {
        Agent agent = resolveAgent(agentId);
        if (agent == null) {
            PipelineContext ctx = new PipelineContext();
            ctx.setUserId(normalizeUserId(userId));
            ctx.setTraceId(EchoMindTrace.currentTraceId());
            ctx.markFailed("No agents configured");
            return ctx;
        }
        PipelineContext ctx = buildPipelineContext(userId, agent, sessionId, userMessage, modelId, attachments,
            rawUserMessage);
        ctx.setMemoryPersistenceEnabled(memoryPersistenceEnabled);

        log.info("[Orchestrator] Agent={} model={} session={} msg={}", agent.getAgentId(),
            ctx.getModelId(), ctx.getSessionId(),
            userMessage.substring(0, Math.min(50, userMessage.length())));

        Span span = orchestrationSpan("echomind.agent.orchestrate", ctx);
        try (Scope ignored = span.makeCurrent()) {
            ctx.setTraceId(EchoMindTrace.traceId(span));
            return agent.chat(ctx);
        } catch (RuntimeException e) {
            EchoMindTrace.recordException(span, e);
            throw e;
        } finally {
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
        return Mono.defer(() -> {
            Agent agent = resolveAgent(agentId);
            if (agent == null) {
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

            PipelineContext ctx = buildPipelineContext(userId, agent, sessionId, userMessage, modelId, attachments,
                rawUserMessage);

            log.info("[Orchestrator:stream] Agent={} model={} session={} msg={}", agent.getAgentId(),
                ctx.getModelId(), ctx.getSessionId(),
                userMessage.substring(0, Math.min(50, userMessage.length())));

            Span span = orchestrationSpan("echomind.agent.orchestrate_stream", ctx);
            try (Scope ignored = span.makeCurrent()) {
                ctx.setTraceId(EchoMindTrace.traceId(span));
                Flux<String> tokens = agent.chatStream(ctx)
                    .doOnError(error -> EchoMindTrace.recordException(span, error))
                    .doFinally(signalType -> span.end());
                return Mono.just(new StreamExecution(ctx, tokens));
            } catch (RuntimeException e) {
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
