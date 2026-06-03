package com.echomind.agent.tool.mcp;

import com.echomind.agent.tool.router.CapabilityRegistry;
import com.echomind.common.exception.MCPTransportException;
import com.echomind.mcp.ToolProvider;
import com.echomind.mcp.ToolResult;
import com.echomind.mcp.ToolSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部 MCP Server 运行时管理服务。
 *
 * <p>职责只有一个：管理"外部 MCP 服务接入"。它不实现 MCP Server，不监听 stdio/http，
 * 因此不会把 EchoMind 自己暴露给其他 MCP Client。</p>
 *
 * <p>挂载流程：
 * <ol>
 *   <li>根据配置创建 MCP Client transport；</li>
 *   <li>完成 MCP initialize 握手并读取 tools/list；</li>
 *   <li>把这些工具注册进 {@link CapabilityRegistry}，供 Agent 的模型工具调用使用；</li>
 *   <li>保存 client/provider 引用，后续可动态刷新或卸载。</li>
 * </ol>
 *
 * <p>卸载流程会先从能力注册中心移除工具，再关闭 MCP client，避免停用后的 MCP 工具继续被旧对话调用。</p>
 */
@Slf4j
public class ExternalMcpRuntimeService implements AutoCloseable {

    private static final String SOURCE_PREFIX = "mcp:";
    private static final String TRANSPORT_STDIO = "stdio";
    private static final String TRANSPORT_SSE = "sse";
    private static final String TRANSPORT_STREAMABLE_HTTP = "streamable-http";
    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final CapabilityRegistry capabilityRegistry;
    private final Map<String, MountedServer> mountedServers = new ConcurrentHashMap<>();

    public ExternalMcpRuntimeService(CapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    /**
     * 挂载一个外部 MCP Server。
     *
     * @param config 挂载配置
     * @return 挂载后的运行态快照
     */
    public synchronized ExternalMcpServerStatus mount(ExternalMcpServerConfig config) {
        ExternalMcpServerConfig normalized = normalize(config);
        String id = normalized.id();
        if (mountedServers.containsKey(id)) {
            throw new IllegalArgumentException("MCP服务已挂载：" + id);
        }

        McpClientTransport transport = createTransport(normalized);
        McpSyncClient client = McpClient.sync(transport)
            .clientInfo(new McpSchema.Implementation("echomind", "1.0.0"))
            .requestTimeout(DEFAULT_TIMEOUT)
            .initializationTimeout(DEFAULT_TIMEOUT)
            .build();

        try {
            client.initialize();

            McpSyncToolProvider toolProvider = new McpSyncToolProvider(client, id);
            List<ToolSpec> tools = List.copyOf(toolProvider.getTools());
            ensureNoToolConflict(id, tools);

            MountedServer mounted = new MountedServer(normalized, client, toolProvider, Instant.now(), tools);
            registerMountedServer(mounted);
            mountedServers.put(id, mounted);
            log.info("External MCP server {} mounted with {} tool(s)", id, tools.size());
            return toStatus(mounted);
        } catch (RuntimeException ex) {
            client.close();
            throw ex;
        } catch (Exception ex) {
            client.close();
            throw new IllegalArgumentException("MCP服务挂载失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 卸载外部 MCP Server。
     *
     * @param id 服务标识
     * @return 卸载前的运行态快照
     */
    public synchronized ExternalMcpServerStatus unmount(String id) {
        String normalizedId = requireId(id);
        MountedServer mounted = mountedServers.remove(normalizedId);
        if (mounted == null) {
            throw new IllegalArgumentException("MCP服务未挂载：" + normalizedId);
        }
        ExternalMcpServerStatus status = toStatus(mounted);
        unregisterMountedServer(mounted);
        mounted.client.close();
        log.info("External MCP server {} unmounted", normalizedId);
        return status;
    }

    /**
     * 重新读取一个 MCP Server 的工具列表。
     *
     * <p>如果外部服务运行中新增或删除了工具，可以通过刷新让 Agent 可见工具保持同步。</p>
     *
     * @param id 服务标识
     * @return 刷新后的运行态快照
     */
    public synchronized ExternalMcpServerStatus refresh(String id) {
        String normalizedId = requireId(id);
        MountedServer mounted = mountedServers.get(normalizedId);
        if (mounted == null) {
            throw new IllegalArgumentException("MCP服务未挂载：" + normalizedId);
        }

        try {
            List<ToolSpec> previousTools = mounted.tools;
            List<ToolSpec> tools = List.copyOf(mounted.toolProvider.refresh());
            ensureNoToolConflict(normalizedId, mounted.toolProvider, tools);
            unregisterMountedServer(mounted, previousTools);
            mounted.tools = tools;
            mounted.error = null;
            registerMountedServer(mounted);
            log.info("External MCP server {} refreshed with {} tool(s)", normalizedId, tools.size());
        } catch (RuntimeException ex) {
            mounted.error = ex.getMessage();
            throw ex;
        }
        return toStatus(mounted);
    }

    /**
     * 当前所有外部 MCP Server 快照。
     */
    public List<ExternalMcpServerStatus> listServers() {
        return mountedServers.values().stream()
            .sorted(Comparator.comparing(server -> server.config.id()))
            .map(this::toStatus)
            .toList();
    }

    /**
     * 当前所有外部 MCP 工具。
     */
    public List<ToolSpec> listTools() {
        return capabilityRegistry.listTools();
    }

    /**
     * 调用已挂载外部 MCP 工具。
     */
    public ToolResult callTool(String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName不能为空");
        }
        return capabilityRegistry.callTool(toolName, arguments == null ? Map.of() : arguments);
    }

    @Override
    public synchronized void close() {
        List<String> ids = new ArrayList<>(mountedServers.keySet());
        for (String id : ids) {
            try {
                unmount(id);
            } catch (Exception ex) {
                log.warn("External MCP server {} close failed: {}", id, ex.getMessage());
            }
        }
    }

    // ---- transport creation ----

    private McpClientTransport createTransport(ExternalMcpServerConfig config) {
        return switch (config.transport()) {
            case TRANSPORT_STDIO -> createStdioTransport(config);
            case TRANSPORT_SSE -> createSseTransport(config);
            case TRANSPORT_STREAMABLE_HTTP -> createStreamableHttpTransport(config);
            default -> throw new IllegalArgumentException("不支持的MCP传输方式：" + config.transport());
        };
    }

    private McpClientTransport createStdioTransport(ExternalMcpServerConfig config) {
        List<String> command = config.command();
        String executable = command.get(0);
        List<String> args = command.size() > 1 ? command.subList(1, command.size()) : List.of();
        ServerParameters params = ServerParameters.builder(executable)
            .args(args)
            .env(config.environment())
            .build();
        Path workingDirectory = config.workingDirectory() == null || config.workingDirectory().isBlank()
            ? null
            : Path.of(config.workingDirectory());
        return new EchoMindStdioClientTransport(params, JSON_MAPPER, workingDirectory);
    }

    private McpClientTransport createSseTransport(ExternalMcpServerConfig config) {
        HttpClientSseClientTransport.Builder builder = HttpClientSseClientTransport.builder(config.url())
            .jsonMapper(JSON_MAPPER)
            .clientBuilder(HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT))
            .customizeRequest(request -> applyHeaders(request, config.headers()))
            .connectTimeout(DEFAULT_TIMEOUT);
        if (config.endpoint() != null && !config.endpoint().isBlank()) {
            builder.sseEndpoint(config.endpoint());
        }
        return builder.build();
    }

    private McpClientTransport createStreamableHttpTransport(ExternalMcpServerConfig config) {
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport.builder(config.url())
            .jsonMapper(JSON_MAPPER)
            .clientBuilder(HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT))
            .customizeRequest(request -> applyHeaders(request, config.headers()))
            .connectTimeout(DEFAULT_TIMEOUT);
        if (config.endpoint() != null && !config.endpoint().isBlank()) {
            builder.endpoint(config.endpoint());
        }
        return builder.build();
    }

    /**
     * 应用请求头到 HTTP 请求。
     *
     * @param request HTTP 请求构建器
     * @param headers 请求头映射
     */
    private void applyHeaders(HttpRequest.Builder request, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        headers.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null) {
                request.header(name, value);
            }
        });
    }

    // ---- schema mapping ----

    static ToolSpec toToolSpec(McpSchema.Tool tool) {
        if (tool == null) {
            return new ToolSpec("", "", Map.of("type", "object", "properties", Map.of()));
        }
        return new ToolSpec(
            tool.name(),
            description(tool),
            toInputSchema(tool.inputSchema())
        );
    }

    static ToolResult toToolResult(McpSchema.CallToolResult result) {
        if (result == null) {
            return ToolResult.error("MCP tool returned no result");
        }
        List<ToolResult.ContentItem> content = result.content() == null
            ? List.of()
            : result.content().stream().map(ExternalMcpRuntimeService::toContentItem).toList();
        if (content.isEmpty() && result.structuredContent() != null) {
            content = List.of(new ToolResult.ContentItem("json", stringify(result.structuredContent())));
        }
        return new ToolResult(content, Boolean.TRUE.equals(result.isError()));
    }

    private static String description(McpSchema.Tool tool) {
        if (tool.description() != null && !tool.description().isBlank()) {
            return tool.description();
        }
        return tool.title() == null ? "" : tool.title();
    }

    private static Map<String, Object> toInputSchema(McpSchema.JsonSchema schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", schema == null || schema.type() == null || schema.type().isBlank()
            ? "object"
            : schema.type());
        result.put("properties", schema == null || schema.properties() == null
            ? Map.of()
            : schema.properties());
        if (schema != null && schema.required() != null && !schema.required().isEmpty()) {
            result.put("required", schema.required());
        }
        if (schema != null && schema.additionalProperties() != null) {
            result.put("additionalProperties", schema.additionalProperties());
        }
        if (schema != null && schema.defs() != null && !schema.defs().isEmpty()) {
            result.put("$defs", schema.defs());
        }
        if (schema != null && schema.definitions() != null && !schema.definitions().isEmpty()) {
            result.put("definitions", schema.definitions());
        }
        return result;
    }

    private static ToolResult.ContentItem toContentItem(McpSchema.Content content) {
        if (content instanceof McpSchema.TextContent text) {
            return new ToolResult.ContentItem("text", text.text());
        }
        if (content instanceof McpSchema.ImageContent image) {
            return new ToolResult.ContentItem("image", image.data());
        }
        return new ToolResult.ContentItem(content == null ? "unknown" : content.type(), stringify(content));
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    // ---- registration helpers ----

    private void registerMountedServer(MountedServer mounted) {
        String sourceId = sourceId(mounted.config.id());
        capabilityRegistry.registerToolProvider(mounted.toolProvider);
        capabilityRegistry.registerAll(MCPToolProviderAdapter.adapt(
            mounted.toolProvider,
            sourceId,
            mounted.config.toolMetadata()
        ));
    }

    private void unregisterMountedServer(MountedServer mounted) {
        unregisterMountedServer(mounted, mounted.tools);
    }

    private void unregisterMountedServer(MountedServer mounted, List<ToolSpec> tools) {
        String sourceId = sourceId(mounted.config.id());
        capabilityRegistry.unregisterBySourceId(sourceId);
        unregisterMcpProviderTools(tools);
    }

    private void unregisterMcpProviderTools(List<ToolSpec> tools) {
        if (tools == null) {
            return;
        }
        for (ToolSpec tool : tools) {
            if (tool != null && tool.name() != null) {
                capabilityRegistry.unregisterTool(tool.name());
            }
        }
    }

    private void ensureNoToolConflict(String serverId, List<ToolSpec> tools) {
        ensureNoToolConflict(serverId, null, tools);
    }

    private void ensureNoToolConflict(String serverId, ToolProvider allowedProvider, List<ToolSpec> tools) {
        String currentSourceId = sourceId(serverId);
        for (ToolSpec tool : tools) {
            capabilityRegistry.get(tool.name())
                .filter(existing -> !currentSourceId.equals(existing.sourceId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("工具名已存在：" + tool.name());
                });
            capabilityRegistry.getToolProvider(tool.name())
                .filter(existing -> existing != allowedProvider)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("MCP工具名已存在：" + tool.name());
                });
        }
    }

    // ---- config normalization ----

    private ExternalMcpServerStatus toStatus(MountedServer mounted) {
        return new ExternalMcpServerStatus(
            mounted.config.id(),
            mounted.config.transport(),
            mounted.config.command(),
            mounted.config.workingDirectory(),
            mounted.config.url(),
            mounted.config.endpoint(),
            mounted.client != null,
            mounted.tools.size(),
            mounted.tools,
            mounted.mountedAt,
            mounted.error
        );
    }

    private ExternalMcpServerConfig normalize(ExternalMcpServerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("MCP服务配置不能为空");
        }
        String id = requireId(config.id());
        String transport = config.transport() == null || config.transport().isBlank()
            ? "stdio"
            : config.transport().trim().toLowerCase();
        if ("streamable_http".equals(transport) || "streamablehttp".equals(transport) || "http".equals(transport)) {
            transport = TRANSPORT_STREAMABLE_HTTP;
        }
        if (!TRANSPORT_STDIO.equals(transport)
            && !TRANSPORT_SSE.equals(transport)
            && !TRANSPORT_STREAMABLE_HTTP.equals(transport)) {
            throw new IllegalArgumentException("不支持的MCP传输方式：" + transport);
        }
        List<String> command = config.command() == null ? List.of() : config.command().stream()
            .filter(part -> part != null && !part.isBlank())
            .map(String::trim)
            .toList();
        if (TRANSPORT_STDIO.equals(transport) && command.isEmpty()) {
            throw new IllegalArgumentException("MCP服务启动命令不能为空");
        }
        String workingDirectory = config.workingDirectory() == null || config.workingDirectory().isBlank()
            ? null
            : config.workingDirectory().trim();
        String url = config.url() == null || config.url().isBlank() ? null : config.url().trim();
        if (!TRANSPORT_STDIO.equals(transport) && url == null) {
            throw new IllegalArgumentException("远程MCP服务URL不能为空");
        }
        String endpoint = config.endpoint() == null || config.endpoint().isBlank() ? null : config.endpoint().trim();
        Map<String, String> environment = config.environment() == null ? Map.of() : Map.copyOf(config.environment());
        Map<String, String> headers = config.headers() == null ? Map.of() : Map.copyOf(config.headers());
        Map<String, ExternalMcpToolMetadata> toolMetadata = normalizeToolMetadata(config.toolMetadata());
        return new ExternalMcpServerConfig(
            id, transport, command, workingDirectory, environment, url, endpoint, headers, toolMetadata);
    }

    private Map<String, ExternalMcpToolMetadata> normalizeToolMetadata(
        Map<String, ExternalMcpToolMetadata> configured) {
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        Map<String, ExternalMcpToolMetadata> normalized = new LinkedHashMap<>();
        configured.forEach((toolName, metadata) -> {
            if (toolName != null && !toolName.isBlank()) {
                normalized.put(toolName.trim(),
                    metadata == null ? ExternalMcpToolMetadata.empty() : metadata);
            }
        });
        return Map.copyOf(normalized);
    }

    private String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("MCP服务ID不能为空");
        }
        return id.trim();
    }

    private String sourceId(String serverId) {
        return SOURCE_PREFIX + serverId;
    }

    // ---- inner types ----

    /**
     * ToolProvider backed directly by an {@link McpSyncClient}.
     */
    @Slf4j
    static final class McpSyncToolProvider implements ToolProvider {

        private final McpSyncClient client;
        private final String serverId;
        private List<ToolSpec> cachedTools;

        McpSyncToolProvider(McpSyncClient client, String serverId) {
            this.client = client;
            this.serverId = serverId;
        }

        @Override
        public List<ToolSpec> getTools() {
            if (cachedTools == null) {
                cachedTools = fetchTools();
            }
            return cachedTools;
        }

        @Override
        public ToolResult call(String toolName, Map<String, Object> arguments) {
            try {
                McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    toolName,
                    arguments == null ? Map.of() : arguments
                );
                return toToolResult(client.callTool(request));
            } catch (Exception ex) {
                throw new MCPTransportException(
                    "Failed to call MCP tool " + toolName + " from " + serverId, ex);
            }
        }

        List<ToolSpec> refresh() {
            cachedTools = fetchTools();
            return cachedTools;
        }

        private List<ToolSpec> fetchTools() {
            try {
                return client.listTools().tools().stream()
                    .map(ExternalMcpRuntimeService::toToolSpec)
                    .toList();
            } catch (Exception ex) {
                throw new MCPTransportException(
                    "Failed to list MCP tools from " + serverId, ex);
            }
        }
    }

    private static final class MountedServer {
        private final ExternalMcpServerConfig config;
        private final McpSyncClient client;
        private final McpSyncToolProvider toolProvider;
        private final Instant mountedAt;
        private volatile List<ToolSpec> tools;
        private volatile String error;

        private MountedServer(ExternalMcpServerConfig config, McpSyncClient client,
                              McpSyncToolProvider toolProvider, Instant mountedAt, List<ToolSpec> tools) {
            this.config = config;
            this.client = client;
            this.toolProvider = toolProvider;
            this.mountedAt = mountedAt;
            this.tools = tools;
        }
    }
}