package com.echomind.console.dto;

import com.echomind.common.model.MessageAttachment;
import java.util.List;

/**
 * HTTP 聊天请求体。
 */
public record ChatMessageRequest(
    String agentId,
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
