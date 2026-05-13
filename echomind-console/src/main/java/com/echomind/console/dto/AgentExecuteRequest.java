package com.echomind.console.dto;

/**
 * Agent调试执行请求。
 */
public record AgentExecuteRequest(
    String sessionId,
    String message
) {}
