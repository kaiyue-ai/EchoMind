package com.echomind.agent.tool.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalMcpRuntimeServiceMappingTest {

    @Test
    void convertsMcpToolSchemaToEchoMindToolSpec() {
        McpSchema.Tool tool = new McpSchema.Tool(
            "remote.search",
            "远程搜索",
            "查询远程搜索服务",
            new McpSchema.JsonSchema(
                "object",
                Map.of("query", Map.of("type", "string", "description", "关键词")),
                List.of("query"),
                false,
                Map.of(),
                Map.of()
            ),
            Map.of(),
            null,
            Map.of()
        );

        var spec = ExternalMcpRuntimeService.toToolSpec(tool);

        assertThat(spec.name()).isEqualTo("remote.search");
        assertThat(spec.description()).isEqualTo("查询远程搜索服务");
        assertThat(spec.inputSchema())
            .containsEntry("type", "object")
            .containsEntry("required", List.of("query"));
        assertThat(((Map<?, ?>) spec.inputSchema().get("properties")).containsKey("query")).isTrue();
    }

    @Test
    void convertsTextToolResult() {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent("完成")),
            false
        );

        var mapped = ExternalMcpRuntimeService.toToolResult(result);

        assertThat(mapped.isError()).isFalse();
        assertThat(mapped.content()).hasSize(1);
        assertThat(mapped.content().get(0).type()).isEqualTo("text");
        assertThat(mapped.content().get(0).text()).isEqualTo("完成");
    }
}
