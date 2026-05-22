package com.echomind.common.model;

/**
 * 主模型给长期记忆链路的轻量信号。
 *
 * <p>它只决定是否优先触发后台合并，不直接作为事实写入长期记忆。</p>
 */
public record MemorySignal(
    boolean important, // 重要信号
    double confidence, // 信号强度
    String reason // 信号原因
) {

    public static final MemorySignal NONE = new MemorySignal(false, 0, "");

    public MemorySignal {
        confidence = Math.max(0, Math.min(1, confidence));
        reason = reason == null ? "" : reason.trim();
    }

    public boolean shouldFlush(double threshold) {
        return important && confidence >= threshold;
    }
}
