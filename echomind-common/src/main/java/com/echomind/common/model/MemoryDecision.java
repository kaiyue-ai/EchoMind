package com.echomind.common.model;

/**
 * 主模型对本轮用户长期记忆的内部决策。
 *
 * <p>普通聊天历史和 Redis 短期上下文每轮都必须保存；这里的两个开关只控制是否异步沉淀
 * 用户事实和刷新用户画像。解析失败时走保守降级，避免漏掉可能重要的长期记忆。</p>
 */
public record MemoryDecision(
    boolean rememberFacts,
    boolean refreshProfile,
    boolean parseValid,
    String reason
) {

    public static final MemoryDecision NONE = new MemoryDecision(false, false, true, "");

    public static final MemoryDecision FALLBACK = new MemoryDecision(
        true,
        true,
        false,
        "主模型记忆决策解析失败，降级触发用户事实和画像异步处理"
    );

    public MemoryDecision {
        reason = reason == null ? "" : reason.trim();
    }

    public boolean shouldProcessUserMemory() {
        return rememberFacts || refreshProfile;
    }
}
