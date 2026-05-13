package com.echomind.console.controller.rest;

import com.echomind.console.dto.McpToolCallRequest;
import com.echomind.console.service.McpApplicationService;
import com.echomind.agent.tool.ExternalMcpServerConfig;
import com.echomind.agent.tool.ExternalMcpServerStatus;
import com.echomind.mcp.tool.ToolResult;
import com.echomind.mcp.tool.ToolSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 外部MCP管理控制器。
 *
 * <p>这里提供的是“挂载/卸载外部MCP Server”和“调用外部MCP工具”的管理入口。
 * 本项目不再暴露自己的MCP Server信息。</p>
 *
 * <p>Controller只负责HTTP协议适配，工具注册、参数提取和调用细节统一交给
 * {@link McpApplicationService}，避免Web层直接依赖MCP运行时。</p>
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class MCPController {

    private final McpApplicationService mcpService;

    /** 列出当前已挂载的外部MCP服务。 */
    @GetMapping("/servers")
    public ResponseEntity<List<ExternalMcpServerStatus>> listServers() {
        return ResponseEntity.ok(mcpService.listServers());
    }

    /** 动态挂载一个外部MCP服务。 */
    @PostMapping("/servers")
    public ResponseEntity<ExternalMcpServerStatus> mount(@RequestBody ExternalMcpServerConfig request) {
        return ResponseEntity.ok(mcpService.mount(request));
    }

    /** 卸载一个外部MCP服务，并从Agent工具视图移除它的工具。 */
    @DeleteMapping("/servers/{serverId}")
    public ResponseEntity<ExternalMcpServerStatus> unmount(@PathVariable String serverId) {
        return ResponseEntity.ok(mcpService.unmount(serverId));
    }

    /** 重新读取一个外部MCP服务的工具列表。 */
    @PostMapping("/servers/{serverId}/refresh")
    public ResponseEntity<ExternalMcpServerStatus> refresh(@PathVariable String serverId) {
        return ResponseEntity.ok(mcpService.refresh(serverId));
    }

    /**
     * 列出所有已挂载外部MCP服务提供的工具。
     */
    @GetMapping("/tools")
    public ResponseEntity<List<ToolSpec>> listTools() {
        return ResponseEntity.ok(mcpService.listTools());
    }

    /**
     * 调用指定的外部MCP工具。
     *
     * @param toolName 工具名称
     * @param request 请求体，arguments字段为工具入参
     */
    @PostMapping("/tools/{toolName}/call")
    public ResponseEntity<ToolResult> callTool(@PathVariable String toolName,
                                               @RequestBody(required = false) McpToolCallRequest request) {
        return ResponseEntity.ok(mcpService.callTool(toolName, request));
    }

}
