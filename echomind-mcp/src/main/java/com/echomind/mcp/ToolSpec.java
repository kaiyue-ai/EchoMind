package com.echomind.mcp;

import java.util.Map;

/**
 * 工具规格记录 —— 描述 MCP 服务端注册的一个工具。
 *
 * <p>此 Record 是 MCP 协议中 "tools/list" 响应的数据模型，
 * 定义了工具的元信息，供 MCP 客户端发现和调用工具时使用。
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li><b>name</b> —— 工具的唯一名称（如 "weather.current"、"search.web"）。
 *       在同一个 MCP Server 中必须唯一，是工具调用的标识符。</li>
 *   <li><b>description</b> —— 工具的人类可读描述，用于 AI 模型理解工具的用途。
 *       建议使用清晰的自然语言描述输入输出。</li>
 *   <li><b>inputSchema</b> —— 工具的输入参数 JSON Schema（Map 形式）。
 *       用于校验调用参数的合法性，并提供给 AI 模型决定何时调用该工具。</li>
 * </ul>
 *
 * <p>设计决策：
 * 使用 Java Record —— 不可变、轻量级、自带序列化/反序列化支持（Jackson 原生支持 Record 类）。
 *
 * @author EchoMind Team
 * @see ToolProvider
 * @see ToolResult
 * @since 1.0
 */
public record ToolSpec(
    /**
     * 工具名称 —— 在 MCP Server 中唯一标识一个工具。
     * 命名建议：使用小写字母和点号分隔，如 "weather.current"、"search.web"。
     */
    String name,

    /**
     * 工具描述 —— 人类可读的工具功能说明。
     * 此描述会被传递给 LLM，帮助模型判断何时应该调用此工具以及如何构造参数。
     */
    String description,

    /**
     * 输入参数 JSON Schema —— 定义工具接受的参数结构。
     * 格式为标准 JSON Schema Map，包含 type、properties、required 等字段。
     * 用于参数校验和 LLM 的工具选择决策。
     */
    Map<String, Object> inputSchema
) {}
