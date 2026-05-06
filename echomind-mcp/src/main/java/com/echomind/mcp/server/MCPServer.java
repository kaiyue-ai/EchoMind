package com.echomind.mcp.server;

import com.echomind.mcp.jsonrpc.JsonRpcMessage;
import com.echomind.mcp.server.transport.StdioTransport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 服务器 —— Model Context Protocol 服务端实现。
 *
 * <p>作为 MCP 2024-11-05 协议的服务端，MCPServer 负责：
 * <ol>
 *   <li><b>工具注册与管理：</b>通过 {@link #registerToolProvider(ToolProvider)}
 *       将工具提供者注册到服务器中。支持动态注册和注销。</li>
 *   <li><b>请求路由与处理：</b>解析 JSON-RPC 消息，根据 method 字段路由到
 *       对应的处理方法（initialize、tools/list、tools/call）。</li>
 *   <li><b>传输层管理：</b>通过 {@link StdioTransport} 进行基于标准输入/输出的
 *       JSON-RPC 通信，支持 MCP 子进程协议模式。</li>
 *   <li><b>协议握手：</b>处理 "initialize" 请求，返回服务器名称、版本和协议版本。</li>
 * </ol>
 *
 * <h3>支持的方法</h3>
 * <table>
 *   <tr><th>Method</th><th>处理逻辑</th><th>用途</th></tr>
 *   <tr><td>initialize</td><td>返回协议版本、服务名称和版本</td><td>协议握手</td></tr>
 *   <tr><td>tools/list</td><td>返回所有已注册工具的规格列表</td><td>工具发现</td></tr>
 *   <tr><td>tools/call</td><td>调用指定工具并返回结果</td><td>工具执行</td></tr>
 * </table>
 *
 * <p>线程安全：
 * 使用 {@link ConcurrentHashMap} 存储工具提供者映射，支持并发注册和调用。
 *
 * <p>设计决策：
 * <ul>
 *   <li>传输层抽象 —— 构造时使用 {@link StdioTransport}，消息处理逻辑通过
 *       {@link StdioTransport#StdioTransport(java.util.function.Function)} 注入，
 *       便于未来扩展到 HTTP、WebSocket 等传输方式。</li>
 *   <li>工具去重 —— {@link #listTools()} 使用 {@link HashSet} 对 ToolProvider 实例去重，
 *       防止同一提供者注册多次导致工具列表重复。</li>
 *   <li>错误隔离 —— 每个处理方法内部捕获异常，返回标准的 JSON-RPC 错误响应
 *       （-32601 方法未找到，-32603 内部错误），不会导致服务器崩溃。</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see ToolProvider
 * @see ToolSpec
 * @see ToolResult
 * @see StdioTransport
 * @since 1.0
 */
public class MCPServer {

    private static final Logger log = LoggerFactory.getLogger(MCPServer.class);

    /** 共享的 Jackson ObjectMapper —— 用于 JSON-RPC 消息的序列化与反序列化 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** MCP 服务器名称 —— 在 "initialize" 握手响应中返回 */
    private final String serverName;
    /** MCP 服务器版本号 —— 在 "initialize" 握手响应中返回 */
    private final String serverVersion;

    /**
     * 工具注册映射 —— Key 为工具名称，Value 为提供该工具的 ToolProvider。
     * 使用 ConcurrentHashMap 支持线程安全的动态注册。
     * 注意：由于一个 ToolProvider 可能注册多个工具，因此不同 Key 可能指向同一个 Value。
     */
    private final Map<String, ToolProvider> toolProviders = new ConcurrentHashMap<>();

    /** 标准输入/输出传输层 —— 负责 JSON-RPC 消息的接收和发送 */
    private final StdioTransport transport;

    /**
     * 构造 MCP 服务器实例。
     *
     * @param serverName    服务器名称（用于 initialize 握手）
     * @param serverVersion 服务器版本号（用于 initialize 握手）
     */
    public MCPServer(String serverName, String serverVersion) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.transport = new StdioTransport(this::handleMessage);
    }

    /**
     * 注册工具提供者 —— 将其提供的所有工具加入工具目录。
     *
     * <p>对 ToolProvider 的 {@link ToolProvider#getTools()} 返回的每个工具，
     * 以工具名为 Key 将提供者注册到映射中。
     *
     * @param provider 要注册的工具提供者
     */
    public void registerToolProvider(ToolProvider provider) {
        for (ToolSpec tool : provider.getTools()) {
            toolProviders.put(tool.name(), provider);
            log.info("Registered MCP tool: {}", tool.name());
        }
    }

    /**
     * 注销工具提供者 —— 将其提供的所有工具从工具目录中移除。
     *
     * <p>注意：如果多个提供者注册了同名工具，此方法会一并移除。
     *
     * @param provider 要注销的工具提供者
     */
    public void unregisterToolProvider(ToolProvider provider) {
        for (ToolSpec tool : provider.getTools()) {
            toolProviders.remove(tool.name());
        }
    }

    /**
     * 列出所有已注册工具的去重列表。
     *
     * <p>使用 {@link HashSet} 对 ToolProvider 实例去重，确保同一提供者的
     * 全部工具只出现一次（即便该提供者注册了多个同名工具映射）。
     *
     * @return 当前服务器中所有唯一工具的规格列表
     */
    public List<ToolSpec> listTools() {
        List<ToolSpec> all = new ArrayList<>();
        Set<ToolProvider> seen = new HashSet<>();
        for (ToolProvider provider : toolProviders.values()) {
            if (seen.add(provider)) {
                all.addAll(provider.getTools());
            }
        }
        return all;
    }

    /**
     * 调用指定工具并返回执行结果。
     *
     * <p>如果工具未注册，返回 isError=true 的 ToolResult，而不是抛出异常。
     *
     * @param toolName  要调用的工具名称
     * @param arguments 工具调用参数
     * @return 工具执行结果（成功或错误）
     */
    public ToolResult callTool(String toolName, Map<String, Object> arguments) {
        ToolProvider provider = toolProviders.get(toolName);
        if (provider == null) {
            return ToolResult.error("Tool not found: " + toolName);
        }
        return provider.call(toolName, arguments);
    }

    /**
     * 处理传入的 JSON-RPC 消息 —— 根据 method 字段路由到对应的处理方法。
     *
     * <p>路由逻辑：
     * <ul>
     *   <li>非请求消息（如通知、响应）直接返回空字符串不处理。</li>
     *   <li>根据 method 值使用 switch 表达式分发：
     *     <ul>
     *       <li>"initialize" → {@link #handleInitialize(JsonRpcMessage)}</li>
     *       <li>"tools/list" → {@link #handleListTools(JsonRpcMessage)}</li>
     *       <li>"tools/call" → {@link #handleCallTool(JsonRpcMessage)}</li>
     *       <li>其他 → 返回 -32601 Method not found 错误</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param message 传入的 JSON-RPC 消息
     * @return 序列化后的 JSON-RPC 响应字符串
     */
    public String handleMessage(JsonRpcMessage message) {
        try {
            if (!message.isRequest()) {
                return "";
            }
            return switch (message.method()) {
                case "initialize" -> handleInitialize(message);
                case "tools/list" -> handleListTools(message);
                case "tools/call" -> handleCallTool(message);
                default -> MAPPER.writeValueAsString(
                    JsonRpcMessage.error(message.id(), -32601, "Method not found: " + message.method()));
            };
        } catch (Exception e) {
            log.error("Error handling MCP message", e);
            try {
                return MAPPER.writeValueAsString(
                    JsonRpcMessage.error(message.id(), -32603, "Internal error: " + e.getMessage()));
            } catch (JsonProcessingException ex) {
                return "";
            }
        }
    }

    /**
     * 处理 "initialize" 方法 —— 返回 MCP 协议握手信息。
     *
     * <p>响应内容：
     * <ul>
     *   <li>protocolVersion —— 固定为 "2024-11-05"（MCP 规范版本）</li>
     *   <li>serverName —— 本 MCP 服务器的名称</li>
     *   <li>serverVersion —— 本 MCP 服务器的版本号</li>
     * </ul>
     *
     * @param msg 原始 JSON-RPC 请求消息
     * @return 序列化后的 JSON-RPC 成功响应
     * @throws JsonProcessingException 当 JSON 序列化失败时抛出
     */
    private String handleInitialize(JsonRpcMessage msg) throws JsonProcessingException {
        ObjectNode result = MAPPER.createObjectNode()
            .put("protocolVersion", "2024-11-05")
            .put("serverName", serverName)
            .put("serverVersion", serverVersion);
        return MAPPER.writeValueAsString(JsonRpcMessage.response(msg.id(), result));
    }

    /**
     * 处理 "tools/list" 方法 —— 返回所有已注册工具的列表。
     *
     * <p>调用 {@link #listTools()} 获取工具列表，包装为 {"tools": [...]} 格式返回。
     *
     * @param msg 原始 JSON-RPC 请求消息
     * @return 序列化后的 JSON-RPC 成功响应，包含 tools 数组
     * @throws JsonProcessingException 当 JSON 序列化失败时抛出
     */
    private String handleListTools(JsonRpcMessage msg) throws JsonProcessingException {
        List<ToolSpec> tools = listTools();
        JsonNode toolsNode = MAPPER.valueToTree(Map.of("tools", tools));
        return MAPPER.writeValueAsString(JsonRpcMessage.response(msg.id(), toolsNode));
    }

    /**
     * 处理 "tools/call" 方法 —— 调用指定工具并返回结果。
     *
     * <p>从请求参数中提取 toolName 和 arguments：
     * <ul>
     *   <li>params.name —— 工具名称（必需）</li>
     *   <li>params.arguments —— 工具调用参数 Map（必需）</li>
     * </ul>
     *
     * @param msg 原始 JSON-RPC 请求消息，包含工具名和参数
     * @return 序列化后的 JSON-RPC 成功响应，包含 ToolResult
     * @throws JsonProcessingException 当 JSON 序列化失败时抛出
     */
    private String handleCallTool(JsonRpcMessage msg) throws JsonProcessingException {
        JsonNode params = msg.params();
        String toolName = params.path("name").asText();
        Map<String, Object> arguments = MAPPER.convertValue(params.path("arguments"), Map.class);

        ToolResult toolResult = callTool(toolName, arguments);
        JsonNode resultNode = MAPPER.valueToTree(toolResult);
        return MAPPER.writeValueAsString(JsonRpcMessage.response(msg.id(), resultNode));
    }

    /**
     * 启动 MCP 服务器 —— 开始监听标准输入的消息。
     *
     * <p>委托给 {@link StdioTransport#start()}，创建守护线程读取 stdin 中的 JSON-RPC 消息。
     */
    public void start() {
        transport.start();
    }

    /**
     * 停止 MCP 服务器 —— 中断 stdio 阅读线程并清理资源。
     *
     * <p>委托给 {@link StdioTransport#stop()}。
     */
    public void stop() {
        transport.stop();
    }

    /** @return MCP 服务器名称 */
    public String getServerName() { return serverName; }
    /** @return MCP 服务器版本号 */
    public String getServerVersion() { return serverVersion; }
}
