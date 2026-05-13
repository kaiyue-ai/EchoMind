package com.echomind.common.model;

import java.util.List;

/**
 * 聊天响应消息体。
 *
 * <p>由异步消费者发布到 {@code echomind.chat.responses} 队列，
 * SSE 推送服务会根据 requestId 找到订阅方并返回最终结果。</p>
 */
public record ChatResponse(
    String requestId,
    String sessionId,
    String agentId,
    String modelId,
    String response,
    List<String> skillResults,
    String status,
    String error
) {
    public static ChatResponse success(String requestId, String sessionId, String agentId,
                                        String modelId, String response, List<String> skillResults) {
        return new ChatResponse(requestId, sessionId, agentId, modelId, response, skillResults, "OK", null);
    }

    public static ChatResponse error(String requestId, String error) {
        return new ChatResponse(requestId, null, null, null, null, null, "ERROR", error);
    }
}
