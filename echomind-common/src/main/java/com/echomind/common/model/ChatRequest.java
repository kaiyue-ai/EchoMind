package com.echomind.common.model;

import java.util.List;

/**
 * 聊天请求消息体。
 *
 * <p>由异步聊天入口发布到 {@code echomind.chat.requests} 队列，
 * 消费端拿到后再执行 Agent 管线。</p>
 */
public record ChatRequest(
    String requestId,
    String agentId,
    String sessionId,
    String message,
    String modelId,
    List<MessageAttachment> attachments
) {
    /** 兼容旧异步请求：没有附件时仍可按原字段创建。 */
    public ChatRequest(String requestId, String agentId, String sessionId, String message, String modelId) {
        this(requestId, agentId, sessionId, message, modelId, null);
    }
}
