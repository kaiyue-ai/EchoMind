package com.echomind.common.model;

/** 模型服务原生返回的 token 用量。 */ //提示词token+生成token=总token
public record TokenUsage(long promptTokens, long completionTokens, long totalTokens) {

    public TokenUsage {
        promptTokens = Math.max(0, promptTokens); // 确保提示词 token 为非负
        completionTokens = Math.max(0, completionTokens); // 确保生成 token 为非负
        totalTokens = Math.max(0, totalTokens); // 确保总 token 为非负
        if (totalTokens == 0 && (promptTokens > 0 || completionTokens > 0)) { // 如果总 token 为 0，且提示词 token 或生成 token 不为 0
            // 则总 token 为提示词 token 加生成 token
            totalTokens = promptTokens + completionTokens;
        }
    }

    public static TokenUsage of(long promptTokens, long completionTokens, long totalTokens) {
        TokenUsage usage = new TokenUsage(promptTokens, completionTokens, totalTokens);
        return usage.hasTokens() ? usage : null;
    }

    // 额外加上toekn
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
