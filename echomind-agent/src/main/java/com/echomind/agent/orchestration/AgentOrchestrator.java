package com.echomind.agent.orchestration;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.llm.observer.Observable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Agent编排器，负责将用户请求路由到正确的Agent并驱动执行管道。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li><b>Agent路由</b>：根据agentId查找目标Agent，找不到时回退到第一个可用Agent</li>
 *   <li><b>上下文初始化</b>：创建并填充{@link PipelineContext}，包括会话ID、用户消息、系统提示词等</li>
 *   <li><b>管道调度</b>：调用{@link Agent#chat(PipelineContext)}触发完整的五阶段管道执行</li>
 *   <li><b>会话管理</b>：自动为无sessionId的请求生成UUID</li>
 * </ul>
 *
 * <p>请求处理流程：</p>
 * <pre>
 * execute(agentId, sessionId, userMessage)
 *   → 查找Agent → 初始化PipelineContext → agent.chat(ctx)
 *     → [ContextEnrich → ToolResolution → SkillInvocation → ResultAggregation → MemoryPersist]
 *       → 返回 PipelineContext（含finalResponse）
 * </pre>
 *
 * <p>可观测性：{@code execute}方法标注了{@link Observable}注解，
 * 通过AOP自动记录每次编排调用的入参、返回值和耗时。</p>
 *
 * @author EchoMind Team
 * @see Agent
 * @see AgentFactory
 * @see PipelineContext
 */
public class AgentOrchestrator {

    /** SLF4J日志记录器，用于记录编排过程和错误 */
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    /** Agent工厂，提供Agent的创建和查找能力 */
    private final AgentFactory agentFactory;

    /**
     * 构造Agent编排器。
     *
     * @param agentFactory Agent工厂
     */
    public AgentOrchestrator(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    /**
     * 编排并执行一次完整的Agent对话请求。
     *
     * <p>此方法封装了从请求到响应的完整流程：</p>
     * <ol>
     *   <li>通过{@link AgentFactory#get(String)}查找目标Agent</li>
     *   <li>如果未找到指定Agent，回退到第一个可用Agent</li>
     *   <li>如果没有任何Agent，返回错误响应</li>
     *   <li>初始化{@link PipelineContext}（会话ID、用户消息、系统提示词、模型ID）</li>
     *   <li>调用{@link Agent#chat(PipelineContext)}执行管道</li>
     * </ol>
     *
     * @param agentId     目标Agent的唯一标识符（如果为null则自动选择）
     * @param sessionId   会话标识符（如果为null则自动生成UUID）
     * @param userMessage 用户输入的文本消息
     * @return 处理完成后的管道上下文，可通过{@link PipelineContext#getFinalResponse()}获取响应
     */
    @Observable("orchestrator.execute")
    public PipelineContext execute(String agentId, String sessionId, String userMessage) {
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
        ctx.setModelId(agent.getDefaultModelId());

        log.info("[Orchestrator] Agent={} session={} msg={}", agent.getAgentId(),
            ctx.getSessionId(), userMessage.substring(0, Math.min(50, userMessage.length())));

        return agent.chat(ctx);
    }
}
