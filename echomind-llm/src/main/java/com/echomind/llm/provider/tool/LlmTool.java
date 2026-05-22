package com.echomind.llm.provider.tool;

import java.util.Map;
import java.util.function.Function;

/**
 * LLM 层使用的中立工具描述。
 *
 * <p>Agent 模块会把 Skill/MCP 工具适配成这个结构，Provider 只负责按模型协议
 * 暴露工具并回调执行函数，避免 LLM 模块反向依赖 Agent 模块。</p>
 */
public record LlmTool(
    /** 符合模型函数命名规则的工具名。 */
    String name,
    /** 给模型看的工具说明。 */
    String description,
    /** JSON Schema 格式的参数定义。 */
    Map<String, Object> parameters,
    /** 工具输出是否已经是最终可交付文本。 */
    boolean directResult,
    /** 以 JSON 字符串参数执行工具，并返回工具输出。 */
    Function<String, String> callback
) {
    public LlmTool(String name, String description, Map<String, Object> parameters,
                   Function<String, String> callback) {
        this(name, description, parameters, false, callback);
    }

    public String call(String argumentsJson) {
        String args = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        return callback.apply(args);
    }
}
