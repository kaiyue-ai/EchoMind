package com.echomind.mcp.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 消息记录 —— MCP 协议的基础通信单元。
 *
 * <p>根据 MCP 2024-11-05 规范，所有 MCP 通信都采用 JSON-RPC 2.0 协议格式。
 * 此 Record 封装了 JSON-RPC 请求和响应的所有标准字段，并提供工厂方法
 * 来创建符合规范的请求、响应和错误消息。
 *
 * <h3>JSON-RPC 2.0 字段说明</h3>
 * <ul>
 *   <li><b>jsonrpc</b> —— 协议版本号，固定为 "2.0"。</li>
 *   <li><b>method</b> —— 方法名，仅在请求中非 null（如 "tools/list"、"tools/call"）。</li>
 *   <li><b>params</b> —— 方法参数，JSON 对象。MCP 中通常包含工具名、参数等信息。</li>
 *   <li><b>id</b> —— 请求标识符，用于匹配请求与响应。可以是 String 或 Number。</li>
 *   <li><b>result</b> —— 成功响应时的返回值，仅在响应中非 null。</li>
 *   <li><b>error</b> —— 错误响应时的错误对象，包含 code 和 message 字段。</li>
 * </ul>
 *
 * <p>序列化策略：
 * 使用 {@link JsonInclude.Include#NON_NULL} 忽略 null 字段，
 * 确保 JSON 输出中只出现有意义的字段（如响应中不含 method 字段）。
 *
 * <p>设计决策：
 * 使用 Java Record —— 不可变、简洁、自带 equals/hashCode/toString，
 * 非常适合作为序列化的消息载体。
 *
 * @author EchoMind Team
 * @see com.echomind.mcp.server.MCPServer
 * @see com.echomind.mcp.client.MCPClient
 * @since 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcMessage(
    /**
     * JSON-RPC 协议版本号，始终为 "2.0"。
     * 在序列化时该字段不会为 null。
     */
    String jsonrpc,

    /**
     * 远程方法名。
     * 仅在请求消息中非 null（如 "initialize"、"tools/list"、"tools/call"）。
     * 在响应消息中为 null。
     */
    String method,

    /**
     * 方法参数，JSON 对象节点。
     * 仅在请求消息中非 null。MCP 中典型参数包括 tool name、arguments 等。
     */
    JsonNode params,

    /**
     * 请求标识符，用于将请求与对应的响应匹配。
     * 可以是 String 或 Integer。在同一 MCP 会话中 id 必须唯一。
     */
    Object id,

    /**
     * 成功响应时的返回结果，JSON 节点。
     * 仅在成功响应中非 null。
     */
    JsonNode result,

    /**
     * 错误响应时的错误对象，JSON 节点。
     * 包含标准 JSON-RPC 错误字段：code（错误码，int）和 message（错误描述，string）。
     * 仅在错误响应中非 null。
     */
    JsonNode error
) {
    /**
     * 创建 JSON-RPC 请求消息。
     *
     * @param method 远程方法名（如 "tools/list"）
     * @param params 方法参数（JSON 节点）
     * @param id     请求标识符，用于匹配响应
     * @return 新的请求消息实例
     */
    public static JsonRpcMessage request(String method, JsonNode params, Object id) {
        return new JsonRpcMessage("2.0", method, params, id, null, null);
    }

    /**
     * 创建 JSON-RPC 成功响应消息。
     *
     * @param id     匹配原始请求的标识符
     * @param result 成功返回的结果（JSON 节点）
     * @return 新的成功响应消息实例
     */
    public static JsonRpcMessage response(Object id, JsonNode result) {
        return new JsonRpcMessage("2.0", null, null, id, result, null);
    }

    /**
     * 创建 JSON-RPC 错误响应消息。
     *
     * @param id      匹配原始请求的标识符
     * @param code    JSON-RPC 错误码（如 -32601 表示方法未找到，-32603 表示内部错误）
     * @param message 人类可读的错误描述
     * @return 新的错误响应消息实例
     */
    public static JsonRpcMessage error(Object id, int code, String message) {
        JsonNode errorNode = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
            .objectNode()
            .put("code", code)
            .put("message", message);
        return new JsonRpcMessage("2.0", null, null, id, null, errorNode);
    }

    /**
     * 判断此消息是否为请求（而非响应）。
     *
     * <p>判断依据：method 字段非 null 即为请求。
     *
     * @return 如果 method 不为 null 则返回 true
     */
    public boolean isRequest() {
        return method != null;
    }

    /**
     * 判断此消息是否为响应（而非请求）。
     *
     * <p>判断依据：result 或 error 任一字段非 null 即为响应。
     *
     * @return 如果 result 或 error 不为 null 则返回 true
     */
    public boolean isResponse() {
        return result != null || error != null;
    }
}
