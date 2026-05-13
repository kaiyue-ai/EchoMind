package com.echomind.agent.tool;

/**
 * 统一工具调用的执行结果。
 */
public record ToolResult(
    String output,
    boolean success,
    String error,
    long durationMs
) {
    public static ToolResult success(String output, long durationMs) {
        return new ToolResult(output, true, null, durationMs);
    }

    public static ToolResult failure(String error, long durationMs) {
        return new ToolResult(null, false, error, durationMs);
    }
}
