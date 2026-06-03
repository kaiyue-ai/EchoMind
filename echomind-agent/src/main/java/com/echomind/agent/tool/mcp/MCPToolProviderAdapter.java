package com.echomind.agent.tool.mcp;

import com.echomind.agent.tool.core.Tool;
import com.echomind.agent.tool.core.ToolResult;
import com.echomind.mcp.ToolProvider;
import com.echomind.mcp.ToolSpec;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 将 MCP 暴露的单个工具适配为 Agent 管线内部的 {@link Tool}。
 *
 * <p>一个 {@link ToolProvider} 可能暴露多个工具，因此这里会为每个 MCP 工具创建独立适配器。</p>
 */
public class MCPToolProviderAdapter implements Tool {

    private final ToolProvider provider;
    private final ToolSpec spec;
    private final String sourceId;
    private final ExternalMcpToolMetadata metadata;

    public MCPToolProviderAdapter(ToolProvider provider, ToolSpec spec, String sourceId) {
        this(provider, spec, sourceId, ExternalMcpToolMetadata.empty());
    }

    public MCPToolProviderAdapter(ToolProvider provider, ToolSpec spec, String sourceId,
                                  ExternalMcpToolMetadata metadata) {
        this.provider = provider;
        this.spec = spec;
        this.sourceId = sourceId;
        this.metadata = metadata == null ? ExternalMcpToolMetadata.empty() : metadata;
    }

    /** 为同一个 MCP 工具提供者暴露出的每个工具创建适配器。 */
    public static List<Tool> adapt(ToolProvider provider, String sourceId) {
        return adapt(provider, sourceId, Map.of());
    }

    /** 为同一个 MCP 工具提供者暴露出的每个工具创建适配器，并附加 EchoMind 侧 routing metadata。 */
    public static List<Tool> adapt(ToolProvider provider, String sourceId,
                                   Map<String, ExternalMcpToolMetadata> toolMetadata) {
        List<Tool> tools = new ArrayList<>();
        for (ToolSpec spec : provider.getTools()) {
            ExternalMcpToolMetadata metadata = toolMetadata == null
                ? ExternalMcpToolMetadata.empty()
                : toolMetadata.getOrDefault(spec.name(), ExternalMcpToolMetadata.empty());
            tools.add(new MCPToolProviderAdapter(provider, spec, sourceId, metadata));
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
    public List<String> tags() { return metadata.tags(); }

    @Override
    public List<String> keywords() { return metadata.keywords(); }

    @Override
    public Map<String, List<String>> aliases() { return metadata.aliases(); }

    @Override
    public String sourceType() { return Tool.SOURCE_MCP; }

    @Override
    public String sourceId() { return sourceId; }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        Context otelContext = Context.current();
        return CompletableFuture.supplyAsync(() -> {
            try (Scope ignored = otelContext.makeCurrent()) {
                long start = System.currentTimeMillis();
                try {
                    com.echomind.mcp.ToolResult mcpResult = provider.call(spec.name(), parameters);
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
            }
        });
    }
}
