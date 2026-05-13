package com.echomind.agent.tool;

import com.echomind.mcp.client.StdioMCPClient;
import com.echomind.mcp.client.StdioMCPToolAdapter;
import com.echomind.mcp.tool.ToolProvider;
import com.echomind.mcp.tool.ToolResult;
import com.echomind.mcp.tool.ToolSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部 MCP Server 运行时管理服务。
 *
 * <p>职责只有一个：管理“外部 MCP 服务接入”。它不实现 MCP Server，不监听 stdio/http，
 * 因此不会把 EchoMind 自己暴露给其他 MCP Client。</p>
 *
 * <p>挂载流程：
 * <ol>
 *
 *
 *   <li>根据配置启动外部 MCP 子进程；</li>
 *   <li>完成 MCP initialize 握手并读取 tools/list；</li>
 *   <li>把这些工具注册进 {@link CapabilityRegistry}，供 Agent 的模型工具调用使用；</li>
 *   <li>保存 client/provider 引用，后续可动态刷新或卸载。</li>
 * </ol>
 *
 * <p>卸载流程会先从能力注册中心移除工具，再关闭子进程，避免停用后的 MCP 工具继续被旧对话调用。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ExternalMcpRuntimeService implements AutoCloseable {

    private static final String SOURCE_PREFIX = "mcp:";

    private final CapabilityRegistry capabilityRegistry;
    private final Map<String, MountedServer> mountedServers = new ConcurrentHashMap<>();

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

        Path workingDirectory = normalized.workingDirectory() == null || normalized.workingDirectory().isBlank()
            ? null
            : Path.of(normalized.workingDirectory());
        StdioMCPClient client = new StdioMCPClient(id, normalized.command(), workingDirectory);

        try {
            if (!client.initialize()) {
                throw new IllegalArgumentException("MCP服务初始化失败：" + id);
            }

            StdioMCPToolAdapter provider = new StdioMCPToolAdapter(client);
            List<ToolSpec> tools = List.copyOf(provider.getTools());
            ensureNoToolConflict(id, tools);

            MountedServer mounted = new MountedServer(normalized, client, provider, Instant.now(), tools);
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
            List<ToolSpec> tools = List.copyOf(mounted.provider.refresh());
            ensureNoToolConflict(normalizedId, mounted.provider, tools);
            unregisterMountedServer(mounted);
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

    private void registerMountedServer(MountedServer mounted) {
        String sourceId = sourceId(mounted.config.id());
        capabilityRegistry.registerToolProvider(mounted.provider);
        capabilityRegistry.registerAll(MCPToolProviderAdapter.adapt(mounted.provider, sourceId));
    }

    private void unregisterMountedServer(MountedServer mounted) {
        String sourceId = sourceId(mounted.config.id());
        capabilityRegistry.unregisterBySourceId(sourceId);
        capabilityRegistry.unregisterToolProvider(mounted.provider);
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

    private ExternalMcpServerStatus toStatus(MountedServer mounted) {
        return new ExternalMcpServerStatus(
            mounted.config.id(),
            mounted.config.transport(),
            mounted.config.command(),
            mounted.config.workingDirectory(),
            mounted.client.isRunning(),
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
        if (!"stdio".equals(transport)) {
            throw new IllegalArgumentException("当前只支持stdio MCP服务：" + transport);
        }
        if (config.command() == null || config.command().isEmpty()) {
            throw new IllegalArgumentException("MCP服务启动命令不能为空");
        }
        List<String> command = config.command().stream()
            .filter(part -> part != null && !part.isBlank())
            .map(String::trim)
            .toList();
        if (command.isEmpty()) {
            throw new IllegalArgumentException("MCP服务启动命令不能为空");
        }
        String workingDirectory = config.workingDirectory() == null || config.workingDirectory().isBlank()
            ? null
            : config.workingDirectory().trim();
        return new ExternalMcpServerConfig(id, transport, command, workingDirectory);
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

    private static final class MountedServer {
        private final ExternalMcpServerConfig config;
        private final StdioMCPClient client;
        private final StdioMCPToolAdapter provider;
        private final Instant mountedAt;
        private volatile List<ToolSpec> tools;
        private volatile String error;

        private MountedServer(ExternalMcpServerConfig config, StdioMCPClient client,
                              StdioMCPToolAdapter provider, Instant mountedAt, List<ToolSpec> tools) {
            this.config = config;
            this.client = client;
            this.provider = provider;
            this.mountedAt = mountedAt;
            this.tools = tools;
        }
    }
}
