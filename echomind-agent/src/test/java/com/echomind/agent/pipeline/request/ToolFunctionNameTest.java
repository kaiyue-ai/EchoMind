package com.echomind.agent.pipeline.request;

import com.echomind.agent.tool.core.Tool;
import com.echomind.agent.tool.core.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 函数名规范化测试。
 *
 * <p>工具运行时名称可以按 Skill/MCP 的习惯命名，但交给模型的 function name
 * 必须始终满足供应商协议要求。</p>
 */
class ToolFunctionNameTest {

    @Test
    void keepsAlreadyValidFunctionName() {
        assertThat(ToolFunctionName.from(tool("open_web_search"))).isEqualTo("open_web_search");
    }

    @Test
    void replacesInvalidSeparatorsWithUnderscores() {
        assertThat(ToolFunctionName.from(tool("date-query.v2"))).isEqualTo("date_query_v2");
    }

    @Test
    void prefixesNumericToolName() {
        assertThat(ToolFunctionName.from(tool("12306"))).isEqualTo("tool_12306");
    }

    @Test
    void replacesSpacesAndOtherSymbols() {
        assertThat(ToolFunctionName.from(tool("web search/crawl"))).isEqualTo("web_search_crawl");
    }

    private Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
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
                return Tool.SOURCE_SKILL;
            }

            @Override
            public String sourceId() {
                return name + "@1.0.0";
            }

            @Override
            public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
                return CompletableFuture.completedFuture(ToolResult.success("ok", 1));
            }
        };
    }
}
