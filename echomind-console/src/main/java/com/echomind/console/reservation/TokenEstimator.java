package com.echomind.console.reservation;

/**
 * Token 用量估算工具。
 *
 * <p>普通聊天和 Team 内部模型调用优先使用 {@link #estimateProcessedTokens(String, long)}：
 * 按“输入 token 估算 + 输出 token 上限”预留，避免执行到中途才发现 quota 或 Provider budget 不够。
 * 旧 {@link #estimate(String)} 仅保留给兼容调用点。
 *
 * <h2>旧估算公式</h2>
 * <pre>{@code clamp(ceil(message.length() / 2.5 * 2 * 1.2), 512, 4096)}</pre>
 * <ul>
 *   <li>2.5：中文每字符约 2.5 token</li>
 *   <li>2.0：假设响应长度等于消息长度</li>
 *   <li>1.2：20% 安全缓冲</li>
 *   <li>512-4096：最小/最大预留范围</li>
 * </ul>
 */
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 2.5;
    private static final double RESPONSE_FACTOR = 2.0;
    private static final double SAFETY_FACTOR = 1.2;
    private static final long MIN_RESERVE = 512;
    private static final long MAX_RESERVE = 4096;

    private TokenEstimator() {
    }

    /**
     * 根据消息长度估算 Token 用量。
     *
     * @param message 用户消息
     * @return 估算的 Token 数量（512-4096）
     */
    public static long estimate(String message) {
        if (message == null || message.isBlank()) {
            return MIN_RESERVE;
        }
        long estimated = (long) Math.ceil(
            message.length() / CHARS_PER_TOKEN * RESPONSE_FACTOR * SAFETY_FACTOR
        );
        return Math.max(MIN_RESERVE, Math.min(estimated, MAX_RESERVE));
    }

    /**
     * 估算一次模型请求会占用的处理 Token：输入估算 + 最大输出 Token。
     *
     * @param prompt          本次模型请求的输入文本
     * @param maxOutputTokens 本次模型请求允许的最大输出 Token
     * @return 请求开始前应冻结的 Token 数
     */
    public static long estimateProcessedTokens(String prompt, long maxOutputTokens) {
        return estimateInputTokens(prompt) + Math.max(0, maxOutputTokens);
    }

    private static long estimateInputTokens(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return 0;
        }
        return (long) Math.ceil(prompt.length() / CHARS_PER_TOKEN);
    }
}
