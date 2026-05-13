package com.echomind.agent.tool;

import com.echomind.mcp.tool.ToolProvider;
import com.echomind.mcp.tool.ToolProviderRegistry;
import com.echomind.mcp.tool.ToolSpec;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一能力注册中心。
 *
 * <p>Skill、MCP工具和后续内置工具都先注册为Capability，再由Agent和MCP读取各自需要的视图。
 * 这样启用、禁用、上传和删除能力时，只需要改一个状态源。</p>
 */
@Slf4j
public class CapabilityRegistry extends ToolRouter implements ToolProviderRegistry {

    /** Agent侧工具视图，Key为工具名称。 */
    private final Map<String, Tool> agentTools = new ConcurrentHashMap<>();
    /** MCP侧工具视图，Key为工具名称。 */
    private final Map<String, ToolProvider> mcpToolProviders = new ConcurrentHashMap<>();

    @Override
    protected Collection<Tool> currentTools() {
        return agentTools.values();
    }

    @Override
    public void register(Tool tool) {
        agentTools.put(tool.name(), tool);
        log.info("Registered capability tool: {} (source={}, sourceId={})",
            tool.name(), tool.sourceType(), tool.sourceId());
    }

    @Override
    public void unregister(String toolName) {
        Tool removed = agentTools.remove(toolName);
        if (removed != null) {
            log.info("Unregistered capability tool: {}", toolName);
        }
    }

    @Override
    public void unregisterBySourceId(String sourceId) {
        List<String> toolNames = agentTools.entrySet().stream()
            .filter(entry -> Objects.equals(entry.getValue().sourceId(), sourceId))
            .map(Map.Entry::getKey)
            .toList();
        for (String toolName : toolNames) {
            unregister(toolName);
        }
    }

    @Override
    public Optional<Tool> get(String toolName) {
        return Optional.ofNullable(agentTools.get(toolName));
    }

    @Override
    public Collection<Tool> listAll() {
        return List.copyOf(agentTools.values());
    }

    @Override
    public void registerToolProvider(ToolProvider provider) {
        for (ToolSpec tool : provider.getTools()) {
            mcpToolProviders.put(tool.name(), provider);
            log.info("Registered capability MCP tool: {}", tool.name());
        }
    }

    @Override
    public void unregisterToolProvider(ToolProvider provider) {
        for (ToolSpec tool : provider.getTools()) {
            ToolProvider removed = mcpToolProviders.remove(tool.name(), provider) ? provider : null;
            if (removed != null) {
                log.info("Unregistered capability MCP tool: {}", tool.name());
            }
        }
    }

    @Override
    public void unregisterTool(String toolName) {
        ToolProvider removed = mcpToolProviders.remove(toolName);
        if (removed != null) {
            log.info("Unregistered capability MCP tool: {}", toolName);
        }
    }

    @Override
    public List<ToolSpec> listTools() {
        List<ToolSpec> all = new ArrayList<>();
        Set<ToolProvider> seen = new HashSet<>();
        for (ToolProvider provider : mcpToolProviders.values()) {
            if (seen.add(provider)) {
                all.addAll(provider.getTools());
            }
        }
        return all;
    }

    @Override
    public com.echomind.mcp.tool.ToolResult callTool(String toolName, Map<String, Object> arguments) {
        ToolProvider provider = mcpToolProviders.get(toolName);
        if (provider == null) {
            return com.echomind.mcp.tool.ToolResult.error("Tool not found: " + toolName);
        }
        return provider.call(toolName, arguments);
    }

    @Override
    public Optional<ToolProvider> getToolProvider(String toolName) {
        return Optional.ofNullable(mcpToolProviders.get(toolName));
    }
}
