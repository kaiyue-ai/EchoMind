package com.echomind.agent.tool.core;

/**
 * 统一工具调用的执行结果。
 *
 * @param output 工具成功时返回给模型的文本
 * @param success 工具是否执行成功
 * @param error 工具失败时返回的错误信息
 * @param durationMs 工具执行耗时，单位毫秒
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