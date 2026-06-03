package com.echomind.console.service;

import com.echomind.console.dto.McpToolCallRequest;
import com.echomind.agent.tool.mcp.ExternalMcpRuntimeService;
import com.echomind.agent.tool.mcp.ExternalMcpServerConfig;
import com.echomind.agent.tool.mcp.ExternalMcpServerStatus;
import com.echomind.mcp.ToolResult;
import com.echomind.mcp.ToolSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 外部MCP应用服务测试。
 *
 * <p>这里验证Web层不直接触碰MCP运行时细节，所有挂载、卸载和工具调用都统一交给应用服务。</p>
 */
class McpApplicationServiceTest {

    @Test
    void listServersDelegatesToRuntimeService() {
        ExternalMcpRuntimeService runtime = mock(ExternalMcpRuntimeService.class);
        McpApplicationService service = new McpApplicationService(runtime);
        ExternalMcpServerStatus status = new ExternalMcpServerStatus(
            "nowcoder",
            "stdio",
            List.of("java", "-jar", "nowcoder.jar"),
            null,
            true,
            1,
            List.of(new ToolSpec("fetch_nowcoder", "抓取面经", Map.of())),
            Instant.now(),
            null
        );
        when(runtime.listServers()).thenReturn(List.of(status));

        List<ExternalMcpServerStatus> servers = service.listServers();

        assertThat(servers).containsExactly(status);
    }

    @Test
    void callToolPassesSafeArguments() {
        ExternalMcpRuntimeService runtime = mock(ExternalMcpRuntimeService.class);
        McpApplicationService service = new McpApplicationService(runtime);
        ToolResult expected = ToolResult.success("3");
        when(runtime.callTool("calculator", Map.of("expression", "1+2"))).thenReturn(expected);

        ToolResult result = service.callTool(
            "calculator",
            new McpToolCallRequest(Map.of("expression", "1+2"))
        );

        assertThat(result).isSameAs(expected);
        verify(runtime).callTool("calculator", Map.of("expression", "1+2"));
    }

    @Test
    void mountDelegatesToRuntimeService() {
        ExternalMcpRuntimeService runtime = mock(ExternalMcpRuntimeService.class);
        McpApplicationService service = new McpApplicationService(runtime);
        ExternalMcpServerConfig config = new ExternalMcpServerConfig(
            "demo",
            "stdio",
            List.of("java", "-jar", "demo.jar"),
            null
        );
        ExternalMcpServerStatus status = new ExternalMcpServerStatus(
            "demo",
            "stdio",
            config.command(),
            null,
            true,
            0,
            List.of(),
            Instant.now(),
            null
        );
        when(runtime.mount(config)).thenReturn(status);

        assertThat(service.mount(config)).isSameAs(status);
        verify(runtime).mount(config);
    }

    @Test
    void blankToolNameIsRejectedBeforeRuntimeCall() {
        ExternalMcpRuntimeService runtime = mock(ExternalMcpRuntimeService.class);
        McpApplicationService service = new McpApplicationService(runtime);

        assertThatThrownBy(() -> service.callTool(" ", new McpToolCallRequest(Map.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("toolName不能为空");

        verifyNoInteractions(runtime);
    }
}
