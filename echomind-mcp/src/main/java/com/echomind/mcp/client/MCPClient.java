package com.echomind.mcp.client;

import com.echomind.common.exception.MCPTransportException;
import com.echomind.mcp.jsonrpc.JsonRpcMessage;
import com.echomind.mcp.tool.ToolResult;
import com.echomind.mcp.tool.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 客户端 —— 与远程 MCP 服务器通信的 HTTP 客户端实现。
 *
 * <p>MCPClient 通过 HTTP POST 向远程 MCP 服务器发送 JSON-RPC 请求，
 * 支持：
 * <ul>
 *   <li><b>协议初始化：</b>{@link #initialize()} 发送 "initialize" 请求并验证协议版本。</li>
 *   <li><b>工具发现：</b>{@link #listTools()} 获取远程服务器注册的所有工具列表。</li>
 *   <li><b>工具调用：</b>{@link #callTool(String, Map)} 调用远程工具并返回结果。</li>
 * </ul>
 *
 * <h3>通信协议</h3>
 * <ul>
 *   <li>端点：{@code <baseUrl>/mcp}，通过 HTTP POST 发送 JSON-RPC 请求。</li>
 *   <li>请求格式：标准 JSON-RPC 2.0 格式，Content-Type 为 application/json。</li>
 *   <li>连接超时 10 秒，请求超时 30 秒。</li>
 *   <li>请求 ID 使用 {@link AtomicInteger} 自增生成，保证同一客户端会话中 ID 唯一。</li>
 * </ul>
 *
 * <p>错误处理：
 * 网络异常和 JSON 解析异常统一包装为 {@link MCPTransportException}。
 * 工具层面的业务错误（工具未找到、执行失败等）通过 {@link ToolResult#isError()} 区分。
 *
 * <p>设计决策：
 * <ul>
 *   <li>使用 Java 11 {@link java.net.http.HttpClient} —— 原生 HTTP/2 支持，
 *       比 Apache HttpClient 更轻量，无需额外依赖。</li>
 *   <li>同步阻塞 API —— MCP 工具调用通常在 Agent 流水线的同步阶段执行，
 *       不需要异步 HTTP 客户端。如有并发调用需求，调用方可使用 CompletableFuture 包装。</li>
 *   <li>Logger 声明在文件底部 —— 遵循某些代码风格指南，
 *       将静态 final 字段按重要性排序（核心工具在前，辅助工具在后）。</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see MCPToolAdapter
 * @since 1.0
 */
@Slf4j
public class MCPClient {

    /** 共享的 Jackson ObjectMapper —— 用于 JSON-RPC 消息的序列化与反序列化 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 请求 ID 生成器 —— 原子自增计数器。
     * 初始值为 1，每次 {@link #sendRequest(String, JsonNode)} 调用后自增。
     * 使用 AtomicInteger 保证多线程并发调用时的 ID 唯一性。
     */
    private static final AtomicInteger REQUEST_ID = new AtomicInteger(1);

    /** HTTP 客户端 —— 使用 Java 11 原生 HttpClient，连接超时 10 秒 */
    private final HttpClient httpClient;
    /** 远程 MCP 服务器的基础 URL（如 "http://localhost:8080"） */
    private final String baseUrl;
    /** 本客户端的名称 —— 在 "initialize" 握手时发送给服务器 */
    private final String clientName;

    /**
     * 构造 MCP 客户端实例。
     *
     * @param baseUrl    远程 MCP 服务器的基础 URL（不含 "/mcp" 路径后缀）
     * @param clientName 客户端名称，用于 initialize 握手标识
     */
    public MCPClient(String baseUrl, String clientName) {
        this.baseUrl = baseUrl;
        this.clientName = clientName;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * 获取远程 MCP 服务器注册的所有工具列表。
     *
     * <p>发送 "tools/list" JSON-RPC 请求，从响应的 result.tools 中反序列化为
     * {@link ToolSpec} 列表。
     *
     * @return 远程服务器的工具规格列表
     * @throws MCPTransportException 当网络请求失败或 JSON 解析失败时抛出
     */
    public List<ToolSpec> listTools() {
        try {
            String resp = sendRequest("tools/list", MAPPER.createObjectNode());
            JsonNode root = MAPPER.readTree(resp);
            JsonNode tools = root.path("result").path("tools");
            return MAPPER.convertValue(tools,
                MAPPER.getTypeFactory().constructCollectionType(List.class, ToolSpec.class));
        } catch (Exception e) {
            throw new MCPTransportException("Failed to list tools from " + baseUrl, e);
        }
    }

    /**
     * 调用远程 MCP 服务器的指定工具。
     *
     * <p>构造包含 toolName 和 arguments 的 JSON-RPC "tools/call" 请求，
     * 并将响应的 result 字段反序列化为 {@link ToolResult}。
     *
     * @param toolName  要调用的远程工具名称
     * @param arguments 工具调用参数 Map
     * @return 工具执行结果（含成功/失败标志和内容列表）
     * @throws MCPTransportException 当网络请求失败或 JSON 解析失败时抛出
     */
    public ToolResult callTool(String toolName, Map<String, Object> arguments) {
        try {
            ObjectNode params = MAPPER.createObjectNode()
                .put("name", toolName)
                .set("arguments", MAPPER.valueToTree(arguments));
            String resp = sendRequest("tools/call", params);
            JsonNode root = MAPPER.readTree(resp);
            return MAPPER.treeToValue(root.path("result"), ToolResult.class);
        } catch (Exception e) {
            throw new MCPTransportException("Failed to call tool: " + toolName, e);
        }
    }

    /**
     * 初始化与远程 MCP 服务器的连接 —— 发送 "initialize" 握手请求。
     *
     * <p>握手过程：
     * <ol>
     *   <li>发送 "initialize" 请求，携带协议版本和客户端名称。</li>
     *   <li>验证响应中的 protocolVersion 是否为 "2024-11-05"。</li>
     *   <li>版本匹配返回 true，不匹配或请求失败返回 false。</li>
     * </ol>
     *
     * <p>注意：失败时仅记录警告日志并返回 false，不抛出异常。
     * 调用方应检查返回值决定是否继续使用该客户端。
     *
     * @return true 表示握手成功且协议版本兼容；false 表示握手失败
     */
    public boolean initialize() {
        try {
            ObjectNode params = MAPPER.createObjectNode()
                .put("protocolVersion", "2024-11-05")
                .put("clientName", clientName);
            String resp = sendRequest("initialize", params);
            JsonNode root = MAPPER.readTree(resp);
            return "2024-11-05".equals(root.path("result").path("protocolVersion").asText());
        } catch (Exception e) {
            log.warn("MCP initialize failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 向远程 MCP 服务器发送 JSON-RPC 请求。
     *
     * <p>实现细节：
     * <ul>
     *   <li>生成唯一的请求 ID（自增 AtomicInteger）。</li>
     *   <li>构造 {@link JsonRpcMessage#request(String, JsonNode, Object)} 并序列化为 JSON。</li>
     *   <li>通过 HTTP POST 发送到 {@code <baseUrl>/mcp} 端点。</li>
     *   <li>Content-Type 设置为 application/json。</li>
     *   <li>请求超时 30 秒（包括连接时间和响应等待时间）。</li>
     * </ul>
     *
     * @param method JSON-RPC 方法名
     * @param params JSON-RPC 参数（JSON 节点）
     * @return 服务器返回的 JSON 响应字符串
     * @throws Exception 当网络请求失败、超时或响应状态码非 2xx 时抛出
     */
    private String sendRequest(String method, JsonNode params) throws Exception {
        int id = REQUEST_ID.getAndIncrement();
        JsonRpcMessage request = JsonRpcMessage.request(method, params, id);
        String json = MAPPER.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/mcp"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

}
