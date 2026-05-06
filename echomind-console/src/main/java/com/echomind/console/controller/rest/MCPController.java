package com.echomind.console.controller.rest;

import com.echomind.mcp.server.MCPServer;
import com.echomind.mcp.server.SkillToolProvider;
import com.echomind.mcp.server.ToolResult;
import com.echomind.mcp.server.ToolSpec;
import com.echomind.skill.registry.SkillRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）管理控制器。
 * 提供MCP工具的发现、调用和服务器状态查询功能。
 */
@RestController
@RequestMapping("/api/mcp")
public class MCPController {

    private final MCPServer mcpServer;
    private final SkillRegistry skillRegistry;

    public MCPController(MCPServer mcpServer, SkillRegistry skillRegistry) {
        this.mcpServer = mcpServer;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 获取MCP服务器信息。
     */
    @GetMapping("/server")
    public ResponseEntity<Map<String, Object>> getServerInfo() {
        return ResponseEntity.ok(Map.of(
            "name", mcpServer.getServerName(),
            "version", mcpServer.getServerVersion(),
            "toolCount", mcpServer.listTools().size()
        ));
    }

    /**
     * 列出所有已注册的MCP工具。
     */
    @GetMapping("/tools")
    public ResponseEntity<List<ToolSpec>> listTools() {
        return ResponseEntity.ok(mcpServer.listTools());
    }

    /**
     * 调用指定的MCP工具。
     * @param toolName 工具名称
     * @param body 请求体，包含工具调用参数
     */
    @PostMapping("/tools/{toolName}/call")
    public ResponseEntity<ToolResult> callTool(@PathVariable String toolName,
                                                @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) body.getOrDefault("arguments", Map.of());
        ToolResult result = mcpServer.callTool(toolName, arguments);
        return ResponseEntity.ok(result);
    }

    /**
     * 注册新的MCP工具提供者（从已启用的Skill中）。
     * 如果 Skill 直接实现了 ToolProvider 则直接注册，否则通过 SkillToolProvider 适配器包装。
     */
    @PostMapping("/register-skill/{skillId}")
    public ResponseEntity<Map<String, String>> registerSkillAsMCP(@PathVariable String skillId) {
        var skillOpt = skillRegistry.getSkill(skillId);
        if (skillOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Skill not found or disabled: " + skillId));
        }
        try {
            var skill = skillOpt.get();
            com.echomind.mcp.server.ToolProvider tp;
            if (skill instanceof com.echomind.mcp.server.ToolProvider directTp) {
                tp = directTp;
            } else {
                // 自动通过 SkillToolProvider 适配器包装
                tp = new SkillToolProvider(skill, skillId);
            }
            mcpServer.registerToolProvider(tp);
            return ResponseEntity.ok(Map.of("status", "registered", "skillId", skillId,
                "toolCount", String.valueOf(tp.getTools().size())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to register: " + e.getMessage()));
        }
    }
}
