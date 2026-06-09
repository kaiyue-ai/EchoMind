package com.echomind.agent.pipeline.request;

import com.echomind.agent.tool.core.Tool;

/**
 * LLM function calling 使用的工具名规范化规则。
 *
 * <p>运行时工具名允许包含连字符、点号、空格，甚至以数字开头；模型函数名必须满足
 * {@code [A-Za-z_][A-Za-z0-9_]*}。这个类集中处理映射，避免工具暴露阶段和请求构造阶段
 * 各自维护一套规则。</p>
 */
public final class ToolFunctionName {

    private ToolFunctionName() {
    }

    public static String from(Tool tool) {
        String name = tool == null || tool.name() == null ? "" : tool.name();
        String normalized = name.trim().replaceAll("[^A-Za-z0-9_]", "_");
        if (normalized.isBlank()) {
            normalized = "tool_unnamed";
        }
        return normalized.matches("[A-Za-z_][A-Za-z0-9_]*") ? normalized : "tool_" + normalized;
    }
}
