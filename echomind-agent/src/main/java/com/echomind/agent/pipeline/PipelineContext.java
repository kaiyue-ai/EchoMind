package com.echomind.agent.pipeline;

import com.echomind.common.model.AgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管道上下文，在执行管道的五个阶段之间传递数据和状态。
 *
 * <p>PipelineContext是Agent请求处理流程中的核心数据载体，它在管道各阶段间流转，
 * 逐步累积处理结果。每个阶段可以读取和修改上下文中的内容。</p>
 *
 * <p>上下文内容分为三类：</p>
 * <ul>
 *   <li><b>输入数据</b>（由调用方设置）：
 *     <ul>
 *       <li>{@code sessionId} — 会话标识，用于关联历史记录</li>
 *       <li>{@code agentId} — 处理请求的Agent ID</li>
 *       <li>{@code modelId} — 目标模型ID</li>
 *       <li>{@code userMessage} — 用户原始消息</li>
 *       <li>{@code systemPrompt} — 系统提示词</li>
 *     </ul>
 *   </li>
 *   <li><b>中间数据</b>（由管道阶段填充）：
 *     <ul>
 *       <li>{@code messages} — 历史消息列表（由ContextEnrichStage填充）</li>
 *       <li>{@code skillResults} — Skill执行结果列表（由SkillInvocationStage填充）</li>
 *     </ul>
 *   </li>
 *   <li><b>输出数据</b>（由管道阶段生成）：
 *     <ul>
 *       <li>{@code finalResponse} — 最终响应文本（由ResultAggregationStage设置）</li>
 *     </ul>
 *   </li>
 *   <li><b>扩展数据</b>：
 *     <ul>
 *       <li>{@code attributes} — 通用属性映射，用于阶段间传递自定义数据</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>线程安全：{@code attributes}使用{@link ConcurrentHashMap}，{@code messages}和
 * {@code skillResults}使用{@link ArrayList}（在管道单线程执行模型下安全）。</p>
 *
 * @author EchoMind Team
 * @see ExecutionPipeline
 * @see PipelineStage
 */
public class PipelineContext {

    /** 会话标识符，用于关联会话历史记忆 */
    private String sessionId;

    /** 处理此请求的Agent ID */
    private String agentId;

    /** 当前使用的LLM模型ID（格式：providerId:modelName） */
    private String modelId;

    /** 用户原始输入消息 */
    private String userMessage;

    /** 系统提示词，定义Agent的行为准则 */
    private String systemPrompt;

    /** 历史消息列表，由ContextEnrichStage从记忆管理器加载 */
    private final List<AgentMessage> messages = new ArrayList<>();

    /** Skill执行结果列表，每个元素为{@code [skillName]: result}格式的字符串 */
    private final List<String> skillResults = new ArrayList<>();

    /** 最终响应文本，由ResultAggregationStage通过LLM生成 */
    private String finalResponse;

    /**
     * 通用属性映射，用于管道阶段间传递自定义扩展数据。
     * 例如：{@code "skillsInvoked" -> true} 表示有Skill被触发。
     */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /** @return 会话标识符 */
    public String getSessionId() { return sessionId; }
    /** @param sessionId 会话标识符 */
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    /** @return 处理此请求的Agent ID */
    public String getAgentId() { return agentId; }
    /** @param agentId Agent ID */
    public void setAgentId(String agentId) { this.agentId = agentId; }

    /** @return 当前使用的模型ID */
    public String getModelId() { return modelId; }
    /** @param modelId 模型ID */
    public void setModelId(String modelId) { this.modelId = modelId; }

    /** @return 用户原始消息 */
    public String getUserMessage() { return userMessage; }
    /** @param userMessage 用户原始消息 */
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }

    /** @return 系统提示词 */
    public String getSystemPrompt() { return systemPrompt; }
    /** @param systemPrompt 系统提示词 */
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    /** @return 历史消息列表（可变） */
    public List<AgentMessage> getMessages() { return messages; }

    /** @return Skill执行结果列表（可变） */
    public List<String> getSkillResults() { return skillResults; }

    /** @return 最终响应文本 */
    public String getFinalResponse() { return finalResponse; }
    /** @param finalResponse 最终响应文本 */
    public void setFinalResponse(String finalResponse) { this.finalResponse = finalResponse; }

    /** @return 通用扩展属性映射（线程安全） */
    public Map<String, Object> getAttributes() { return attributes; }
}
