package com.echomind.agent;

import java.util.List;

/**
 * Agent配置对象，用于定义Agent的属性并通过{@link AgentFactory}创建Agent实例。
 *
 * <p>这是一个简单的POJO（Plain Old Java Object），包含Agent创建所需的全部配置属性。
 * 配置可以从多种来源获取（YAML文件、环境变量、数据库等），通过此对象统一传递。</p>
 *
 * <p>属性说明：</p>
 * <ul>
 *   <li><b>agentId</b> — Agent唯一标识符，全局唯一</li>
 *   <li><b>name</b> — Agent显示名称</li>
 *   <li><b>systemPrompt</b> — 系统提示词，定义Agent的角色和行为</li>
 *   <li><b>modelId</b> — 默认模型ID，格式{@code providerId:modelName}</li>
 *   <li><b>skillIds</b> — 绑定的Skill ID列表，默认为空列表</li>
 * </ul>
 *
 * <p>配置示例（YAML）：</p>
 * <pre>{@code
 * agentId: "weather-agent"
 * name: "天气助手"
 * systemPrompt: "你是一个专业的天气查询助手..."
 * modelId: "anthropic:claude-sonnet-4-20250514"
 * skillIds:
 *   - "weather-skill@1.0"
 *   - "calculator-skill@1.0"
 * }</pre>
 *
 * @author EchoMind Team
 * @see AgentFactory
 * @see Agent
 */
public class AgentConfig {

    /** Agent唯一标识符 */
    private String agentId;

    /** Agent显示名称 */
    private String name;

    /** 系统提示词，定义Agent的角色和行为准则 */
    private String systemPrompt;

    /** 默认LLM模型ID，格式为providerId:modelName */
    private String modelId;

    /** 绑定的Skill ID列表，默认为空列表 */
    private List<String> skillIds = List.of();

    /** @return Agent唯一标识符 */
    public String getAgentId() { return agentId; }
    /** @param agentId Agent唯一标识符 */
    public void setAgentId(String agentId) { this.agentId = agentId; }

    /** @return Agent显示名称 */
    public String getName() { return name; }
    /** @param name Agent显示名称 */
    public void setName(String name) { this.name = name; }

    /** @return 系统提示词 */
    public String getSystemPrompt() { return systemPrompt; }
    /** @param systemPrompt 系统提示词 */
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    /** @return 默认模型ID */
    public String getModelId() { return modelId; }
    /** @param modelId 默认模型ID */
    public void setModelId(String modelId) { this.modelId = modelId; }

    /** @return 绑定的Skill ID列表 */
    public List<String> getSkillIds() { return skillIds; }
    /** @param skillIds 绑定的Skill ID列表 */
    public void setSkillIds(List<String> skillIds) { this.skillIds = skillIds; }
}
