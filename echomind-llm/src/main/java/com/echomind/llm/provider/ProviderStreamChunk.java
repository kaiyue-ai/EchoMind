package com.echomind.llm.provider;

import com.echomind.common.model.TokenUsage;
import com.echomind.llm.provider.dto.ProviderResponse;

/** 流式 Provider 的文本片段、最终 usage 片段或结构化失败状态。 */
public record ProviderStreamChunk(String content, TokenUsage usage, boolean failed, String failureReason,
                                  boolean modelInvoked) {

    public ProviderStreamChunk {
        content = content == null ? "" : content;
        failureReason = failureReason == null ? null : failureReason.trim();
    }

    public ProviderStreamChunk(String content, TokenUsage usage, boolean failed, String failureReason) {
        this(content, usage, failed, failureReason, true);
    }

    public ProviderStreamChunk(String content, TokenUsage usage) {
        this(content, usage, false, null, true);
    }

    public static ProviderStreamChunk text(String content) {
        return new ProviderStreamChunk(content, null);
    }

    public static ProviderStreamChunk usage(TokenUsage usage) {
        return new ProviderStreamChunk("", usage);
    }

    public static ProviderStreamChunk failure(String reason) {
        String normalized = ProviderResponse.normalizeReason(reason);
        return new ProviderStreamChunk("[Error] " + normalized, null, true, normalized);
    }

    public boolean hasContent() {
        return !content.isEmpty();
    }

    public boolean hasUsage() {
        return usage != null && usage.hasTokens();
    }
}
