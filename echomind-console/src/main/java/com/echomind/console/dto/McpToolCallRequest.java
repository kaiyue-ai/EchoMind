package com.echomind.console.dto;

import java.util.Map;

/**
 * MCP工具调用请求。
 */
public record McpToolCallRequest(
    Map<String, Object> arguments
) {
    public Map<String, Object> safeArguments() {
        return arguments == null ? Map.of() : arguments;
    }
}
