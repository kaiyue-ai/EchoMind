package com.echomind.agent.tool.router;

import com.echomind.agent.tool.core.Tool;
import com.echomind.mcp.ToolProvider;
import com.echomind.mcp.ToolProviderRegistry;
import com.echomind.mcp.ToolSpec;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一能力注册中心。
 *
 * <p>Skill、MCP工具和后续内置工具都先注册为Capability，再由Agent和MCP读取各自需要的视图。
 * 这样启用、禁用、上传和删除能力时，只需要改一个状态源。</p>
 *
 * <p>Agent侧工具视图复用父类 {@link ToolRouter#tools}，MCP侧工具视图单独维护。</p>
 */
@Slf4j
public class CapabilityRegistry extends ToolRouter implements ToolProviderRegistry {

    /** MCP侧工具视图，Key为工具名称。 */
    private final Map<String, ToolProvider> mcpToolProviders = new ConcurrentHashMap<>();

    @Override
    public void register(Tool tool) {
        Tool existing = tools.put(tool.name(), tool);
        if (existing != null) {
            log.warn("Tool name conflict: '{}' already registered by {}/{}, overwritten by {}/{}",
                tool.name(), existing.sourceType(), existing.sourceId(),
                tool.sourceType(), tool.sourceId());
        } else {
            log.info("Registered capability tool: {} (source={}, sourceId={})",
                tool.name(), tool.sourceType(), tool.sourceId());
        }
    }

    @Override
    public void registerToolProvider(ToolProvider provider) {
        for (ToolSpec tool : provider.getTools()) {
            mcpToolProviders.put(tool.name(), provider);
            log.info("Registered capability MCP tool: {}", tool.name());
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
    public com.echomind.mcp.ToolResult callTool(String toolName, Map<String, Object> arguments) {
        ToolProvider provider = mcpToolProviders.get(toolName);
        if (provider == null) {
            return com.echomind.mcp.ToolResult.error("Tool not found: " + toolName);
        }
        return provider.call(toolName, arguments);
    }

    @Override
    public Optional<ToolProvider> getToolProvider(String toolName) {
        return Optional.ofNullable(mcpToolProviders.get(toolName));
    }
}