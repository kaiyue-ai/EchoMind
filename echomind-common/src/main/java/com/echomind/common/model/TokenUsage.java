package com.echomind.common.model;

/** 模型服务原生返回的 token 用量。 */
public record TokenUsage(long promptTokens, long completionTokens, long totalTokens) {

    public TokenUsage {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        totalTokens = Math.max(0, totalTokens);
        if (totalTokens == 0 && (promptTokens > 0 || completionTokens > 0)) {
            totalTokens = promptTokens + completionTokens;
        }
    }

    public static TokenUsage of(long promptTokens, long completionTokens, long totalTokens) {
        TokenUsage usage = new TokenUsage(promptTokens, completionTokens, totalTokens);
        return usage.hasTokens() ? usage : null;
    }

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
            promptTokens + other.promptTokens,
            completionTokens + other.completionTokens,
            totalTokens + other.totalTokens
        );
    }

    public boolean hasTokens() {
        return promptTokens > 0 || completionTokens > 0 || totalTokens > 0;
    }
}
