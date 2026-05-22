package com.echomind.common.model;

/**
 * 前端 SSE 连接
 *      ↓
 * Controller 把请求投递到 RabbitMQ
 *      ↓ (异步)
 * 消费者执行 Agent → 流式输出 token → 逐条发布 ChatStreamEvent
 *      ↓
 * SSE 层按 requestId 过滤事件 → 直接推送给前端
 *      ↓
 * 前端收到 result/failure → 关闭 SSE
 */
public record ChatStreamEvent(
    String requestId,
    String type,
    String token,
    String sessionId,
    String traceId,
    ChatResponse response,
    String error
) {
    public static final String TYPE_META = "meta";
    public static final String TYPE_TOKEN = "token";
    public static final String TYPE_RESULT = "result";
    public static final String TYPE_FAILURE = "failure";

    public static ChatStreamEvent meta(String requestId, String sessionId, String traceId) {
        return new ChatStreamEvent(requestId, TYPE_META, null, sessionId, traceId, null, null);
    }

    public static ChatStreamEvent token(String requestId, String token) {
        return new ChatStreamEvent(requestId, TYPE_TOKEN, token, null, null, null, null);
    }

    public static ChatStreamEvent result(ChatResponse response) {
        return new ChatStreamEvent(response.requestId(), TYPE_RESULT, null,
            response.sessionId(), response.traceId(), response, null);
    }

    public static ChatStreamEvent failure(String requestId, String error, String traceId) {
        return new ChatStreamEvent(requestId, TYPE_FAILURE, null, null, traceId, null, error);
    }

    public boolean terminal() {
        return TYPE_RESULT.equals(type) || TYPE_FAILURE.equals(type);
    }
}
