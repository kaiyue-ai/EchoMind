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
    String error,
    String toolName,
    long durationMs,
    String traceparent
) {
    public static final String TYPE_META = "meta";
    public static final String TYPE_TOKEN = "token";
    public static final String TYPE_RESULT = "result";
    public static final String TYPE_FAILURE = "failure";
    public static final String TYPE_TOOL_START = "tool_start";
    public static final String TYPE_TOOL_END = "tool_end";

    public static ChatStreamEvent meta(String requestId, String sessionId, String traceId) {
        return new ChatStreamEvent(requestId, TYPE_META, null, sessionId, traceId, null, null, null, 0, null);
    }

    public static ChatStreamEvent token(String requestId, String token) {
        return new ChatStreamEvent(requestId, TYPE_TOKEN, token, null, null, null, null, null, 0, null);
    }

    public static ChatStreamEvent result(ChatResponse response) {
        return new ChatStreamEvent(response.requestId(), TYPE_RESULT, null,
            response.sessionId(), response.traceId(), response, null, null, 0, response.traceparent());
    }

    public static ChatStreamEvent failure(String requestId, String error, String traceId) {
        return new ChatStreamEvent(requestId, TYPE_FAILURE, null, null, traceId, null, error, null, 0, null);
    }

    public static ChatStreamEvent toolStart(String requestId, String toolName) {
        return new ChatStreamEvent(requestId, TYPE_TOOL_START, null, null, null, null, null, toolName, 0, null);
    }

    public static ChatStreamEvent toolEnd(String requestId, String toolName, long durationMs) {
        return new ChatStreamEvent(requestId, TYPE_TOOL_END, null, null, null, null, null, toolName,
            Math.max(durationMs, 0), null);
    }

    public ChatStreamEvent withTrace(String traceId, String traceparent) {
        return new ChatStreamEvent(requestId, type, token, sessionId,
            firstNonBlank(this.traceId, traceId), response, error, toolName, durationMs, traceparent);
    }

    public boolean terminal() {
        return TYPE_RESULT.equals(type) || TYPE_FAILURE.equals(type);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
