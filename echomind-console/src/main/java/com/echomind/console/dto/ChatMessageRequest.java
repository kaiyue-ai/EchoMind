package com.echomind.console.dto;

import com.echomind.common.model.MessageAttachment;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * HTTP 聊天请求体。
 */
public record ChatMessageRequest(
    String agentId,
    @Size(max = 20_000, message = "message 不能超过 20000 个字符")
    String message,
    String sessionId,
    String modelId,
    List<MessageAttachment> attachments
) {
    /** 兼容纯文本请求。 */
    public ChatMessageRequest(String agentId, String message, String sessionId, String modelId) {
        this(agentId, message, sessionId, modelId, null);
    }
}
