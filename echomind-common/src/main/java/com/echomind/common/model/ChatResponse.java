package com.echomind.common.model;

import java.util.List;

/**
 * 聊天响应消息体。
 *
 * <p>由异步消费者作为 {@link ChatStreamEvent#TYPE_RESULT} 载荷交给 SSE 推送服务。</p>
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
    ErrorDetail errorDetail,
    String traceId,
    TokenUsage tokenUsage,
    String traceparent
) {
    public static ChatResponse success(String requestId, String sessionId, String agentId,
                                        String modelId, String response, List<String> skillResults, String traceId) {
        return success(requestId, sessionId, agentId, modelId, response, skillResults, traceId, null);
    }

    public static ChatResponse success(String requestId, String sessionId, String agentId,
                                        String modelId, String response, List<String> skillResults, String traceId,
                                        TokenUsage tokenUsage) {
        return success(requestId, sessionId, agentId, modelId, response, skillResults, traceId, tokenUsage, null);
    }

    public static ChatResponse success(String requestId, String sessionId, String agentId,
                                        String modelId, String response, List<String> skillResults, String traceId,
                                        TokenUsage tokenUsage, String traceparent) {
        return new ChatResponse(requestId, sessionId, agentId, modelId, response, skillResults, "OK", null,
            null, traceId, tokenUsage, traceparent);
    }

    public static ChatResponse success(String requestId, String sessionId, String agentId,
                                        String modelId, String response, List<String> skillResults) {
        return success(requestId, sessionId, agentId, modelId, response, skillResults, null);
    }

    public static ChatResponse error(String requestId, String error, String traceId) {
        return error(requestId, error, traceId, null);
    }

    public static ChatResponse error(String requestId, String error, String traceId, String traceparent) {
        return error(requestId, error, null, traceId, traceparent);
    }

    public static ChatResponse error(String requestId, String error, ErrorDetail errorDetail, String traceId,
                                     String traceparent) {
        return new ChatResponse(requestId, null, null, null, null, null, "ERROR", error, errorDetail, traceId, null,
            traceparent);
    }

    public static ChatResponse error(String requestId, String error) {
        return error(requestId, error, null);
    }
}
