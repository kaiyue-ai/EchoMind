package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.tool.Tool;
import com.echomind.agent.tool.ToolResult;
import com.echomind.agent.tool.ToolRouter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExposurePlannerTest {

    @Test
    void keywordMatchNarrowsToolsAndRequiresSingleMatchedTool() {
        ToolRouter router = new ToolRouter();
        router.register(tool("web-search", "skill", "web-search@1.0.0",
            List.of("search", "direct-result"), List.of("搜索")));
        router.register(tool("calculator", "skill", "calculator@1.0.0",
            List.of("math"), List.of("计算")));
        ToolExposurePlanner planner = new ToolExposurePlanner(router);

        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("帮我搜索 JVM");
        ctx.getAttributes().put("agentSkillIds", List.of("web-search", "calculator"));

        ToolExposure exposure = planner.plan(ctx);

        assertThat(exposure.tools()).extracting(Tool::name).containsExactly("web-search");
        assertThat(exposure.requiredToolName()).isEqualTo("web_search");
        assertThat(ctx.getAttributes()).containsEntry("toolMatchMode", "keyword");
        assertThat(planner.directResult(exposure.tools().get(0))).isTrue();
    }

    @Test
    void modelFallbackExposesAllowedToolsWithoutRequiredTool() {
        ToolRouter router = new ToolRouter();
        router.register(tool("web-search", "skill", "web-search@1.0.0", List.of("search"), List.of("搜索")));
        router.register(tool("calculator", "skill", "calculator@1.0.0", List.of("math"), List.of("计算")));
        ToolExposurePlanner planner = new ToolExposurePlanner(router);

        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("随便聊聊");
        ctx.getAttributes().put("agentSkillIds", List.of("web-search", "calculator"));

        ToolExposure exposure = planner.plan(ctx);

        assertThat(exposure.tools()).extracting(Tool::name)
            .containsExactlyInAnyOrder("web-search", "calculator");
        assertThat(exposure.requiredToolName()).isNull();
        assertThat(ctx.getAttributes()).containsEntry("toolMatchMode", "model");
    }

    @Test
    void functionNameNormalizesInvalidLeadingName() {
        ToolExposurePlanner planner = new ToolExposurePlanner(new ToolRouter());

        assertThat(planner.functionName(tool("12306", "skill", "12306@1.0.0", List.of(), List.of())))
            .isEqualTo("tool_12306");
        assertThat(planner.functionName(tool("web-search", "skill", "web-search@1.0.0", List.of(), List.of())))
            .isEqualTo("web_search");
    }

    private Tool tool(String name, String sourceType, String sourceId, List<String> tags, List<String> keywords) {
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
                return Map.of();
            }

            @Override
            public List<String> tags() {
                return tags;
            }

            @Override
            public List<String> keywords() {
                return keywords;
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
