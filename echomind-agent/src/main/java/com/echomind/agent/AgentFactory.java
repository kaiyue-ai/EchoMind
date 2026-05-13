package com.echomind.agent;

import com.echomind.agent.pipeline.ExecutionPipeline;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;

/**
 * Agent工厂，负责根据配置创建和管理Agent实例。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li><b>Agent创建</b>：根据{@link AgentConfig}配置构造Agent实例</li>
 *   <li><b>实例管理</b>：维护已创建Agent的注册表，支持按ID查找</li>
 *   <li><b>管道共享</b>：所有Agent共享同一个{@link ExecutionPipeline}实例</li>
 * </ul>
 *
 * <p>设计决策：</p>
 * <ul>
 *   <li>所有Agent共享同一个管道实例——管道阶段是无状态的，可以复用</li>
 *   <li>使用{@link ConcurrentHashMap}存储Agent，支持线程安全的并发创建和查询</li>
 *   <li>Agent创建后立即存入注册表，{@link #allAgents()}返回不可变快照</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see Agent
 * @see AgentConfig
 * @see ExecutionPipeline
 */
@RequiredArgsConstructor
public class AgentFactory {

    /** 共享的执行管道，所有Agent使用同一个管道实例处理请求 */
    // 这个管道是多阶段的编排器，处理任务
    private final ExecutionPipeline pipeline;

    /**
     * Agent实例注册表，以agentId为键的线程安全映射。
     * 支持运行时动态创建和查询Agent。
     */
    private final Map<String, Agent> agents = new ConcurrentHashMap<>();

    /**
     * 根据配置创建新的Agent实例。
     *
     * <p>创建完成后自动存入内部注册表，可通过{@link #get(String)}按ID查询。
     * 如果AgentId已存在，此方法会覆盖旧实例（先创建后放入）。</p>
     *
     * @param config Agent的配置信息
     * @return 新创建的Agent实例
     */
    public Agent create(AgentConfig config) {
        Agent agent = new Agent(
            config.getAgentId(),
            config.getName(),
            config.getSystemPrompt(),
            config.getModelId(),
            config.getSkillIds(),
            pipeline
        );
        agents.put(config.getAgentId(), agent);
        return agent;
    }

    /**
     * 按ID查找已创建的Agent。
     *
     * @param agentId Agent的唯一标识符
     * @return Agent实例，如果未找到则返回null
     */
    public Agent get(String agentId) {
        return agents.get(agentId);
    }

    /**
     * 获取所有已创建Agent的不可变快照。
     *
     * <p>返回的Map是当前注册表在调用时刻的不可变副本，
     * 后续的Agent创建不会反映在此Map中。</p>
     *
     * @return agentId到Agent实例的不可变映射
     */
    public Map<String, Agent> allAgents() {
        return Map.copyOf(agents);
    }
}
