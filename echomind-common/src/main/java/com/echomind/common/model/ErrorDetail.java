package com.echomind.common.model;

/**
 * 结构化错误详情，用于 SSE 推送和前端展示。
 */
public record ErrorDetail(
    String code,
    String message,
    String stage,
    String scope,
    Long limitTokens,
    Long usedTokens,
    String resetsAt,
    Long retryAfter,
    Long remaining,
    String providerId
) {
    public ErrorDetail(String code, String message) {
        this(code, message, null, null, null, null, null, null, null, null);
    }
}
