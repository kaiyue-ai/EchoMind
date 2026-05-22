package com.echomind.common.model;

import java.util.List;

/**
 * 聊天响应消息体。
 *
 * <p>由异步消费者作为 {@link ChatStreamEvent#TYPE_RESULT} 载荷发布到
 * {@code echomind.chat.stream-events} 队列，SSE 推送服务按 requestId 转发给订阅方。</p>
 */
public record ChatResponse(
    String requestId,
    String sessionId,
    String agentId,
    String modelId,
    String response,
    List<String> skillResults,
    String status,
    String error,
    String traceId,
    TokenUsage tokenUsage
) {
    public static ChatResponse success(String requestId, String sessionId, String agentId,
                                        String modelId, String response, List<String> skillResults, String traceId) {
        return success(requestId, sessionId, agentId, modelId, response, skillResults, traceId, null);
    }

    public static ChatResponse success(String requestId, String sessionId, String agentId,
                                        String modelId, String response, List<String> skillResults, String traceId,
                                        TokenUsage tokenUsage) {
        return new ChatResponse(requestId, sessionId, agentId, modelId, response, skillResults, "OK", null, traceId, tokenUsage);
    }

    public static ChatResponse success(String requestId, String sessionId, String agentId,
                                        String modelId, String response, List<String> skillResults) {
        return success(requestId, sessionId, agentId, modelId, response, skillResults, null);
    }

    public static ChatResponse error(String requestId, String error, String traceId) {
        return new ChatResponse(requestId, null, null, null, null, null, "ERROR", error, traceId, null);
    }

    public static ChatResponse error(String requestId, String error) {
        return error(requestId, error, null);
    }
}
