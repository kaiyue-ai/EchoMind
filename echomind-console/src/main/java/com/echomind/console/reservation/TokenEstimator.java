package com.echomind.console.reservation;

/**
 * Token 用量估算工具。
 *
 * <p>根据消息长度估算请求+响应所需的 Token 数量，用于入队前资源预留。
 * 避免固定预留 4096 造成的浪费，让用户不吃亏。
 *
 * <h2>估算公式</h2>
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
}