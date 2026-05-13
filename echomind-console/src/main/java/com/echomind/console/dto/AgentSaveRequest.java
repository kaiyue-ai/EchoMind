package com.echomind.console.dto;

import com.echomind.agent.AgentConfig;

import java.util.List;

/**
 * 创建或更新Agent的请求体。
 *
 * <p>接口层使用专用DTO，避免前端协议直接绑定内部运行时配置对象。
 * 转换为{@link AgentConfig}后再交给应用服务执行校验、持久化和运行时注册。</p>
 */
public record AgentSaveRequest(
    String agentId,
    String name,
    String systemPrompt,
    String modelId,
    List<String> skillIds
) {
    public AgentConfig toConfig() {
        AgentConfig config = new AgentConfig();
        config.setAgentId(agentId);
        config.setName(name);
        config.setSystemPrompt(systemPrompt);
        config.setModelId(modelId);
        config.setSkillIds(skillIds == null ? List.of() : skillIds);
        return config;
    }
}
