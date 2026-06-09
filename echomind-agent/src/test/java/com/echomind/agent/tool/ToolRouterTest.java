package com.echomind.agent.tool;

import com.echomind.agent.tool.core.Tool;
import com.echomind.agent.tool.core.ToolResult;
import com.echomind.agent.tool.router.ToolRouter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具注册表测试。
 *
 * <p>普通聊天不再做关键词预筛选，也不再按 Agent skillIds 过滤工具；这里锁住注册、
 * 查询和注销这些运行时索引职责。</p>
 */
class ToolRouterTest {

    @Test
    void listAllReturnsEveryRegisteredTool() {
        ToolRouter router = new ToolRouter();
        router.register(tool("calculator", Tool.SOURCE_SKILL, "calculator@1.0.0"));
        router.register(tool("open_web_search", Tool.SOURCE_MCP, "mcp:open-websearch"));

        assertThat(router.listAll())
            .extracting(Tool::name)
            .containsExactlyInAnyOrder("calculator", "open_web_search");
    }

    @Test
    void registeringSameNameReplacesPreviousTool() {
        ToolRouter router = new ToolRouter();
        router.register(tool("calculator", Tool.SOURCE_SKILL, "calculator@1.0.0"));
        router.register(tool("calculator", Tool.SOURCE_SKILL, "calculator@2.0.0"));

        assertThat(router.listAll()).hasSize(1);
        assertThat(router.get("calculator")).isPresent()
            .get()
            .extracting(Tool::sourceId)
            .isEqualTo("calculator@2.0.0");
    }

    @Test
    void unregisterBySourceIdRemovesOnlyMatchingSourceTools() {
        ToolRouter router = new ToolRouter();
        router.register(tool("open_web_search", Tool.SOURCE_MCP, "mcp:open-websearch"));
        router.register(tool("open_web_fetch_content", Tool.SOURCE_MCP, "mcp:open-websearch"));
        router.register(tool("calculator", Tool.SOURCE_SKILL, "calculator@1.0.0"));

        router.unregisterBySourceId("mcp:open-websearch");

        assertThat(router.listAll())
            .extracting(Tool::name)
            .containsExactly("calculator");
    }

    @Test
    void unregisterRemovesSingleToolByName() {
        ToolRouter router = new ToolRouter();
        router.register(tool("date-query", Tool.SOURCE_SKILL, "date-query@1.0.0"));

        router.unregister("date-query");

        assertThat(router.get("date-query")).isEmpty();
        assertThat(router.listAll()).isEmpty();
    }

    private Tool tool(String name, String sourceType, String sourceId) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " test tool";
            }

            @Override
            public Map<String, Object> parameterSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public List<String> tags() {
                return List.of();
            }

            @Override
            public String sourceType() {
                return sourceType;
            }

            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
                return CompletableFuture.completedFuture(ToolResult.success("ok", 1));
            }
        };
    }
}
