package com.echomind.console.dto;

/**
 * 异步聊天提交后的响应。
 */
public record ChatSubmitResponse(
    String requestId,
    String sessionId,
    String traceId
) {}
