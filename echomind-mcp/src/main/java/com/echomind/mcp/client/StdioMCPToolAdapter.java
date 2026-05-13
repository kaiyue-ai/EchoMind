package com.echomind.mcp.client;

import com.echomind.mcp.tool.ToolProvider;
import com.echomind.mcp.tool.ToolResult;
import com.echomind.mcp.tool.ToolSpec;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 将 stdio MCP 客户端适配成平台内部 ToolProvider。
 *
 * <p>主项目只认 ToolProvider，不关心远端 MCP 是 HTTP 还是 stdio，
 * 所以这里把进程通信细节藏在 {@link StdioMCPClient} 里。</p>
 */
@RequiredArgsConstructor
public class StdioMCPToolAdapter implements ToolProvider {

    private final StdioMCPClient client;
    private List<ToolSpec> cachedTools;

    @Override
    public List<ToolSpec> getTools() {
        if (cachedTools == null) {
            cachedTools = client.listTools();
        }
        return cachedTools;
    }

    @Override
    public ToolResult call(String toolName, Map<String, Object> arguments) {
        return client.callTool(toolName, arguments);
    }

    /**
     * 清空工具列表缓存。
     *
     * <p>外部 MCP Server 运行时变更工具后，管理端刷新服务时会调用这里，
     * 下一次 {@link #getTools()} 会重新通过 tools/list 拉取最新列表。</p>
     */
    public List<ToolSpec> refresh() {
        cachedTools = client.listTools();
        return cachedTools;
    }
}
