package com.echomind.agent.tool;

import com.echomind.mcp.tool.ToolProvider;
import com.echomind.mcp.tool.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 将 MCP 暴露的单个工具适配为 Agent 管线内部的 {@link Tool}。
 *
 * <p>一个 {@link ToolProvider} 可能暴露多个工具，因此这里会为每个 MCP 工具创建独立适配器。</p>
 */
@RequiredArgsConstructor
public class MCPToolProviderAdapter implements Tool {

    @Getter
    private final ToolProvider provider;
    @Getter
    private final ToolSpec spec;
    private final String sourceId;

    /** 为同一个 MCP 工具提供者暴露出的每个工具创建适配器。 */
    public static List<Tool> adapt(ToolProvider provider, String sourceId) {
        List<Tool> tools = new ArrayList<>();
        for (ToolSpec spec : provider.getTools()) {
            tools.add(new MCPToolProviderAdapter(provider, spec, sourceId));
        }
        return tools;
    }

    @Override
    public String name() { return spec.name(); }

    @Override
    public String description() { return spec.description(); }

    @Override
    public Map<String, Object> parameterSchema() { return spec.inputSchema(); }

    @Override
    public List<String> tags() { return List.of(); }

    @Override
    public String sourceType() { return "mcp"; }

    @Override
    public String sourceId() { return sourceId; }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
            com.echomind.mcp.tool.ToolResult mcpResult = provider.call(spec.name(), parameters);
                long duration = System.currentTimeMillis() - start;
                String text = mcpResult.content().stream()
                    .map(c -> c.text())
                    .reduce("", (a, b) -> a + b);
                if (mcpResult.isError()) {
                    return ToolResult.failure(text, duration);
                }
                return ToolResult.success(text, duration);
            } catch (Exception e) {
                return ToolResult.failure(e.getMessage(), System.currentTimeMillis() - start);
            }
        });
    }
}
