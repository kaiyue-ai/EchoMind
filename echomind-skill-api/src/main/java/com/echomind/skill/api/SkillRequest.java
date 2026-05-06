package com.echomind.skill.api;

import java.util.Collections;
import java.util.Map;

/**
 * <h2>技能执行请求</h2>
 * 封装一次技能调用所需的全部输入信息：参数、上下文和调用追踪 ID。
 *
 * <h3>数据结构</h3>
 * <ul>
 *   <li><b>parameters</b> —— 用户/LLM 提供的输入参数（键值对），已通过 JSON Schema 校验</li>
 *   <li><b>context</b> —— 当前会话的上下文信息（会话 ID、智能体 ID、会话属性）</li>
 *   <li><b>toolCallId</b> —— 工具调用的唯一标识符，用于将执行结果关联回原始调用</li>
 * </ul>
 *
 * <h3>不可变性</h3>
 * 使用 Java {@code record} 实现，所有字段为 final，确保请求在传递过程中不会被篡改。
 *
 * <h3>紧凑构造函数</h3>
 * 对 {@code parameters} 字段进行空值防护——当传入 null 时自动替换为不可变的空 Map，
 * 确保技能实现中无需进行 null 检查。
 *
 * @see Skill#execute(SkillRequest) 使用此请求调用技能执行
 * @see SkillContext 请求中包含的会话上下文
 * @see SkillResult 执行完成后的结果封装
 */
public record SkillRequest(
    /**
     * 技能执行的输入参数（键值对映射）。
     * 键为参数名（与 {@code SkillMetadata#parameterSchema} 中的定义对应），
     * 值为 JSON 兼容的数据类型（String、Number、Boolean、List、Map 等）。
     * 平台在调用 {@code execute()} 之前已完成 Schema 校验，确保此处的参数格式合法。
     */
    Map<String, Object> parameters,
    /**
     * 当前会话的上下文信息，包含会话 ID、智能体 ID 和会话级别的属性集合。
     * 技能可以通过此字段访问运行时环境信息（如用户身份、会话偏好等）。
     */
    SkillContext context,
    /**
     * 工具调用的唯一追踪 ID（UUID 字符串）。
     * 用于全链路追踪——同一个 ID 会出现在 {@code ToolCall} 请求、
     * {@code AgentMessage} 消息和日志输出中。
     * 当此请求不由工具调用触发（例如直接由用户指令触发）时，此字段可为 null。
     */
    String toolCallId
) {
    /**
     * 紧凑构造函数：对 {@code parameters} 字段进行空值防护。
     * 当调用方传入 null 时，自动替换为空 Map，避免技能实现中的 NPE 风险。
     *
     * @param parameters 输入参数（null 时自动变为空 Map）
     * @param context    会话上下文
     * @param toolCallId 工具调用 ID（可为 null）
     */
    public SkillRequest {
        parameters = parameters == null ? Collections.emptyMap() : parameters;
    }
}
