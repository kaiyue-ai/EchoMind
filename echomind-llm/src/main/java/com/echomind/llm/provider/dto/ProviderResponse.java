package com.echomind.llm.provider.dto;

import com.echomind.common.model.TokenUsage;

/** Provider 返回的文本、原生 token 用量和结构化失败状态。 */
public record ProviderResponse(String content, TokenUsage usage, boolean failed, String failureReason,
                               boolean modelInvoked) {

    public ProviderResponse {
        content = content == null ? "" : content;
        failureReason = failureReason == null ? null : failureReason.trim();
    }

    public ProviderResponse(String content, TokenUsage usage, boolean failed, String failureReason) {
        this(content, usage, failed, failureReason, true);
    }

    public ProviderResponse(String content, TokenUsage usage) {
        this(content, usage, false, null, true);
    }

    public static ProviderResponse text(String content) {
        return new ProviderResponse(content, null);
    }

    public static ProviderResponse failure(String reason) {
        String normalized = normalizeReason(reason);
        return new ProviderResponse("[Error] " + normalized, null, true, normalized);
    }

    public static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "模型调用失败";
        }
        String trimmed = reason.trim();
        return trimmed.startsWith("[Error]") ? trimmed.substring("[Error]".length()).trim() : trimmed;
    }
}
