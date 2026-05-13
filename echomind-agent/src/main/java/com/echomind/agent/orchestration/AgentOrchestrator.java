package com.echomind.agent.orchestration;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.MessageAttachment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

import reactor.core.publisher.Flux;

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

    /** 执行内部任务，不读取或写入普通聊天记忆。 */
    public PipelineContext executeInternal(String agentId, String sessionId, String userMessage) {
        return execute(agentId, sessionId, userMessage, null, List.of(), false);
    }

    private PipelineContext execute(String agentId, String sessionId, String userMessage, String modelId,
                                    List<MessageAttachment> attachments, boolean memoryPersistenceEnabled) {
        Agent agent = agentFactory.get(agentId);
        if (agent == null) {
            log.warn("Agent not found: {}, using first available", agentId);
            var agents = agentFactory.allAgents();
            if (agents.isEmpty()) {
                PipelineContext ctx = new PipelineContext();
                ctx.setFinalResponse("[Error] No agents configured");
                return ctx;
            }
            agent = agents.values().iterator().next();
        }
        PipelineContext ctx = new PipelineContext();
        ctx.setAgentId(agent.getAgentId());
        ctx.setSessionId(sessionId != null ? sessionId : UUID.randomUUID().toString());
        ctx.setUserMessage(userMessage);
        ctx.setSystemPrompt(agent.getSystemPrompt());
        ctx.setModelId(modelId != null && !modelId.isBlank() ? modelId : agent.getDefaultModelId());
        ctx.setMemoryPersistenceEnabled(memoryPersistenceEnabled);
        if (attachments != null) {
            ctx.getAttachments().addAll(attachments);
        }
        ctx.getAttributes().put("agentSkillIds", agent.getSkillIds());

        log.info("[Orchestrator] Agent={} model={} session={} msg={}", agent.getAgentId(),
            ctx.getModelId(), ctx.getSessionId(),
            userMessage.substring(0, Math.min(50, userMessage.length())));

        return agent.chat(ctx);
    }

    /** 执行流式对话，供 SSE 接口逐 token 推送。 */
    public Flux<String> executeStream(String agentId, String sessionId, String userMessage) {
        return executeStream(agentId, sessionId, userMessage, null);
    }

    /** 执行流式对话，可指定覆盖 Agent 默认模型的 modelId。 */
    public Flux<String> executeStream(String agentId, String sessionId, String userMessage, String modelId) {
        return executeStream(agentId, sessionId, userMessage, modelId, List.of());
    }

    /** 执行流式对话，可携带图片等多模态附件。 */
    public Flux<String> executeStream(String agentId, String sessionId, String userMessage, String modelId,
                                      List<MessageAttachment> attachments) {
        Agent agent = agentFactory.get(agentId);
        if (agent == null) {
            log.warn("Agent not found: {}, using first available", agentId);
            var agents = agentFactory.allAgents();
            if (agents.isEmpty()) {
                return Flux.just("[Error] No agents configured");
            }
            agent = agents.values().iterator().next();
        }

        PipelineContext ctx = new PipelineContext();
        ctx.setAgentId(agent.getAgentId());
        ctx.setSessionId(sessionId != null ? sessionId : UUID.randomUUID().toString());
        ctx.setUserMessage(userMessage);
        ctx.setSystemPrompt(agent.getSystemPrompt());
        ctx.setModelId(modelId != null && !modelId.isBlank() ? modelId : agent.getDefaultModelId());
        if (attachments != null) {
            ctx.getAttachments().addAll(attachments);
        }
        ctx.getAttributes().put("agentSkillIds", agent.getSkillIds());

        log.info("[Orchestrator:stream] Agent={} model={} session={} msg={}", agent.getAgentId(),
            ctx.getModelId(), ctx.getSessionId(),
            userMessage.substring(0, Math.min(50, userMessage.length())));

        return agent.chatStream(ctx);
    }
}
