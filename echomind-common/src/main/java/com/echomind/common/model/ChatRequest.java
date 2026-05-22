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
    String userId,
    String agentId,
    String sessionId,
    String message,
    String modelId,
    String traceId,
    String traceparent, // 这是聊天请求的 Trace 父级，用于链路追踪
    List<MessageAttachment> attachments
) {
    /** 兼容旧异步请求：没有附件时仍可按原字段创建。 */
    public ChatRequest(String requestId, String agentId, String sessionId, String message, String modelId) {
        this(requestId, null, agentId, sessionId, message, modelId, null, null, null);
    }

    /** 兼容旧异步请求：没有用户身份时由消费端回退 default 用户。 */
    public ChatRequest(String requestId, String agentId, String sessionId, String message, String modelId,
                       List<MessageAttachment> attachments) {
        this(requestId, null, agentId, sessionId, message, modelId, null, null, attachments);
    }

    /** 兼容第一阶段用户隔离请求：没有 traceId 时由消费端创建新的消费端 Trace。 */
    public ChatRequest(String requestId, String userId, String agentId, String sessionId, String message, String modelId,
                       List<MessageAttachment> attachments) {
        this(requestId, userId, agentId, sessionId, message, modelId, null, null, attachments);
    }
}
