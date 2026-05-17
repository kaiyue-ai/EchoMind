package com.echomind.agent.pipeline;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MessageAttachment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.Getter;
import lombok.Setter;

/**
 * 管线阶段之间传递的请求上下文。
 *
 * <p>调用方写入用户输入、会话、Agent 和模型偏好；各阶段逐步补充历史消息、工具结果
 * 和最终回复。{@code attributes} 用于少量扩展状态，避免为临时信息频繁改模型。</p>
 */
@Getter
@Setter
public class PipelineContext {

    private String sessionId;
    private String userId = "default";
    private String agentId;
    private String modelId;
    private String userMessage;
    private String systemPrompt;
    private boolean memoryPersistenceEnabled = true;
    private final List<AgentMessage> messages = new ArrayList<>();
    /** 本轮用户上传的附件，只保存对象存储引用，不保存二进制内容。 */
    private final List<MessageAttachment> attachments = new ArrayList<>();
    /** 发给模型的附件，可能是签名 URL 或 data URL，不写入记忆。 */
    private final List<MessageAttachment> modelAttachments = new ArrayList<>();
    /** 兼容旧接口命名：记录本轮模型实际调用过的工具名称。 */
    private final List<String> skillResults = new ArrayList<>();
    private String finalResponse;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /** 判断本轮用户消息是否携带图片。 */
    public boolean hasImageAttachments() {
        return attachments.stream().anyMatch(MessageAttachment::isImage);
    }

    /** 返回模型调用使用的附件；未预处理时退回原始附件。 */
    public List<MessageAttachment> attachmentsForModel() {
        return modelAttachments.isEmpty() ? attachments : modelAttachments;
    }

    /** 记忆按用户和会话隔离，避免不同用户碰巧使用同一个 sessionId 时共享上下文。 */
    public String getMemoryKey() {
        String owner = userId != null && !userId.isBlank() ? userId : "default";
        String conversation = sessionId != null && !sessionId.isBlank() ? sessionId : agentId;
        return owner + ":" + conversation;
    }
}
