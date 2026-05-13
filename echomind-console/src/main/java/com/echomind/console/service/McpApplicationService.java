package com.echomind.console.service;

import com.echomind.console.dto.McpToolCallRequest;
import com.echomind.agent.tool.ExternalMcpRuntimeService;
import com.echomind.agent.tool.ExternalMcpServerConfig;
import com.echomind.agent.tool.ExternalMcpServerStatus;
import com.echomind.mcp.tool.ToolResult;
import com.echomind.mcp.tool.ToolSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 外部MCP应用服务。
 *
 * <p>这里管理的是“接入外部MCP Server”：挂载、卸载、刷新工具、调用工具。
 * EchoMind不再提供把自己暴露成MCP Server的HTTP/stdio入口。</p>
 */
@Service
@RequiredArgsConstructor
public class McpApplicationService {

    private final ExternalMcpRuntimeService runtimeService;

    public List<ExternalMcpServerStatus> listServers() {
        return runtimeService.listServers();
    }

    public ExternalMcpServerStatus mount(ExternalMcpServerConfig request) {
        return runtimeService.mount(request);
    }

    public ExternalMcpServerStatus unmount(String serverId) {
        return runtimeService.unmount(serverId);
    }

    public ExternalMcpServerStatus refresh(String serverId) {
        return runtimeService.refresh(serverId);
    }

    public List<ToolSpec> listTools() {
        return runtimeService.listTools();
    }

    public ToolResult callTool(String toolName, McpToolCallRequest request) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName不能为空");
        }
        return runtimeService.callTool(toolName, request == null ? Map.of() : request.safeArguments());
    }
}
