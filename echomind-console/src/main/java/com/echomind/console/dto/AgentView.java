package com.echomind.console.dto;

import com.echomind.agent.Agent;

import java.util.Collection;

/**
 * Agent 接口返回给前端的展示模型。
 *
 * <p>运行时 Agent 内部持有执行管线等对象，不能直接序列化给接口调用方。</p>
 */
public record AgentView(
    String agentId,
    String name,
    String systemPrompt,
    String defaultModelId,
    Collection<String> skillIds
) {
    public static AgentView from(Agent agent) {
        return new AgentView(
            agent.getAgentId(),
            agent.getName(),
            agent.getSystemPrompt(),
            agent.getDefaultModelId(),
            agent.getSkillIds()
        );
    }
}
