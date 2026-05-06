package com.echomind.agent;

import com.echomind.agent.pipeline.ExecutionPipeline;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.llm.observer.Observable;
import com.echomind.skill.api.SkillResult;

import java.util.List;

/**
 * Agent实例，代表一个拥有特定角色、系统提示词、绑定技能集合和默认模型的智能体。
 *
 * <p>Agent是EchoMind平台的核心抽象之一，每个Agent具有：</p>
 * <ul>
 *   <li><b>身份标识</b>：{@code agentId}唯一标识，{@code name}用于显示</li>
 *   <li><b>行为定义</b>：{@code systemPrompt}定义Agent的角色和行为准则</li>
 *   <li><b>模型绑定</b>：{@code defaultModelId}指定默认使用的LLM模型</li>
 *   <li><b>技能集合</b>：{@code skillIds}列出Agent可调用的Skill ID列表</li>
 *   <li><b>执行管道</b>：{@code pipeline}定义请求处理的五阶段流程</li>
 * </ul>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * PipelineContext ctx = new PipelineContext();
 * ctx.setUserMessage("今天天气怎么样？");
 * ctx.setSessionId(sessionId);
 * PipelineContext result = agent.chat(ctx);
 * String response = result.getFinalResponse();
 * }</pre>
 *
 * <p>可观测性：{@link #chat(PipelineContext)}方法标注了{@link Observable}注解，
 * 通过AOP自动记录每次调用的入参、返回值和耗时。</p>
 *
 * @author EchoMind Team
 * @see ExecutionPipeline
 * @see AgentFactory
 * @see AgentConfig
 */
public class Agent {

    /** Agent的唯一标识符，全局唯一 */
    private final String agentId;

    /** Agent的显示名称，用于UI和日志 */
    private final String name;

    /** 系统提示词，定义Agent的角色、行为准则和输出格式要求 */
    private final String systemPrompt;

    /** 默认使用的LLM模型ID，格式为{@code providerId:modelName}（如anthropic:claude-sonnet-4-20250514） */
    private final String defaultModelId;

    /** Agent绑定的Skill ID列表，Agent可调用这些Skill来完成用户请求 */
    private final List<String> skillIds;

    /** 执行管道，处理从用户输入到最终响应的完整流程 */
    private final ExecutionPipeline pipeline;

    /**
     * 构造一个Agent实例。
     *
     * @param agentId        Agent唯一标识符
     * @param name           显示名称
     * @param systemPrompt   系统提示词
     * @param defaultModelId 默认模型ID
     * @param skillIds       绑定的Skill ID列表
     * @param pipeline       执行管道
     */
    public Agent(String agentId, String name, String systemPrompt,
                 String defaultModelId, List<String> skillIds,
                 ExecutionPipeline pipeline) {
        this.agentId = agentId;
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.defaultModelId = defaultModelId;
        this.skillIds = skillIds;
        this.pipeline = pipeline;
    }

    /**
     * 处理用户对话请求。
     *
     * <p>将{@link PipelineContext}传入执行管道，按序经过五个阶段的处理，
     * 返回包含最终响应和中间结果的PipelineContext。</p>
     *
     * <p>此方法被{@link Observable}注解标记，AOP会自动记录调用信息。</p>
     *
     * @param ctx 包含用户消息和会话信息的管道上下文
     * @return 处理完成后的管道上下文，包含最终响应
     */
    @Observable("agent.chat")
    public PipelineContext chat(PipelineContext ctx) {
        return pipeline.execute(ctx);
    }

    /** @return Agent唯一标识符 */
    public String getAgentId() { return agentId; }

    /** @return Agent显示名称 */
    public String getName() { return name; }

    /** @return 系统提示词 */
    public String getSystemPrompt() { return systemPrompt; }

    /** @return 默认模型ID */
    public String getDefaultModelId() { return defaultModelId; }

    /** @return 绑定的Skill ID列表（不可变） */
    public List<String> getSkillIds() { return skillIds; }
}
