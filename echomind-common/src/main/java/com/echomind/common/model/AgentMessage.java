package com.echomind.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
// 这是agent消息的包装类,能够包装用户消息,agent消息,工具调用的消息
@JsonInclude(JsonInclude.Include.NON_NULL) // 加了这个注解，为null的字段不会出现在序列化输出中
public record AgentMessage(
    /**
     * 消息角色标识，用于区分消息的发送者类型。
     * 有效取值：{@code "user"} / {@code "assistant"} / {@code "system"} / {@code "tool"}。
     */
    String role,
    /**
     * 消息的文本内容或结构化数据（JSON 字符串形式）。
     * 对于 tool 角色，此字段通常包含工具执行返回的结果数据。
     */
    String content,
    /**
     * 消息创建的时间戳（UTC 时间），精确到纳秒。
     * 用于消息排序、滑动窗口管理和对话历史回溯。
     */
    Instant timestamp,
    /**
     * 消息的附加元数据映射（可为 null）。
     * 例如 tool 消息中存放 {@code "toolCallId"} 以关联原始工具调用请求。
     */
    Map<String, Object> metadata,
    /**
     * 消息附件列表（可为 null）。
     * 图片上传后这里只保存对象存储引用和访问 URL，不保存二进制内容。
     */
    List<MessageAttachment> attachments
) {
    /** 兼容旧代码和旧 JSON：没有附件时仍可按四字段方式创建消息。 */
    public AgentMessage(String role, String content, Instant timestamp, Map<String, Object> metadata) {
        this(role, content, timestamp, metadata, null);
    }

    /**
     * 创建一个 {@code "user"} 角色的消息，表示用户的原始输入。
     *
     * @param content 用户输入的文本内容
     * @return 带有当前时间戳的 user 角色消息
     */
    public static AgentMessage user(String content) {
        return new AgentMessage("user", content, Instant.now(), null);
    }

    /** 创建带附件的 {@code "user"} 角色消息。 */
    public static AgentMessage user(String content, List<MessageAttachment> attachments) {
        return new AgentMessage("user", content, Instant.now(), null,
            attachments == null || attachments.isEmpty() ? null : List.copyOf(attachments));
    }

    /**
     * 创建一个 {@code "assistant"} 角色的消息，表示 LLM 的回复产出。
     *
     * @param content LLM 生成的文本回复
     * @return 带有当前时间戳的 assistant 角色消息
     */
    public static AgentMessage assistant(String content) {
        return new AgentMessage("assistant", content, Instant.now(), null);
    }

    /**
     * 创建一个 {@code "system"} 角色的消息，用于注入系统提示或运行时策略。
     * 系统消息通常位于对话窗口的头部，对模型的回答风格、边界和能力进行约束。
     *
     * @param content 系统提示文本
     * @return 带有当前时间戳的 system 角色消息
     */
    public static AgentMessage system(String content) {
        return new AgentMessage("system", content, Instant.now(), null);
    }

    /**
     * 创建一个 {@code "tool"} 角色的消息，用于承载工具/技能调用的执行结果。
     * 自动在元数据中注入 {@code toolCallId} 键，以建立与原始 {@link ToolCall} 请求的关联。
     *
     * @param toolCallId 工具调用的唯一标识符，对应 {@link ToolCall#id()}
     * @param content    工具执行返回的结果字符串
     * @return 带有 toolCallId 元数据的 tool 角色消息
     */
    public static AgentMessage tool(String toolCallId, String content) {
        return new AgentMessage("tool", content, Instant.now(),
            Map.of("toolCallId", toolCallId));
    }

    /**
     * 返回一个新的 {@code AgentMessage} 实例，其核心字段与原消息相同，但替换了元数据。
     * 此方法不修改原消息（不可变设计），而是返回一个全新的副本。
     *
     * @param metadata 新的元数据映射，将完全替代原消息的 metadata 字段
     * @return 带有新元数据的 AgentMessage 副本
     */
    public AgentMessage withMetadata(Map<String, Object> metadata) {
        return new AgentMessage(role, content, timestamp, metadata, attachments);
    }
}
