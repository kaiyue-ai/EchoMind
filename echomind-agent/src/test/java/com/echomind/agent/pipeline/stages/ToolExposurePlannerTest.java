package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.planning.ToolExposure;
import com.echomind.agent.pipeline.planning.ToolExposurePlanner;
import com.echomind.agent.tool.core.Tool;
import com.echomind.agent.tool.core.ToolResult;
import com.echomind.agent.tool.router.ToolRouter;
import com.echomind.common.model.AgentMessage;
import com.echomind.agent.tool.mcp.ExternalMcpToolMetadata;
import com.echomind.agent.tool.mcp.MCPToolProviderAdapter;
import com.echomind.mcp.ToolProvider;
import com.echomind.mcp.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExposurePlannerTest {

    @Test
    void keywordMatchNarrowsToolsAndRequiresSingleMatchedTool() {
        ToolRouter router = new ToolRouter();
        router.register(tool("docs-search", "skill", "docs-search@1.0.0",
            List.of("search"), List.of("搜索")));
        router.register(tool("calculator", "skill", "calculator@1.0.0",
            List.of("math"), List.of("计算")));
        ToolExposurePlanner planner = new ToolExposurePlanner(router);

        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("帮我搜索 JVM");
        ctx.getAttributes().put(PipelineContext.ATTR_AGENT_SKILL_IDS, List.of("docs-search", "calculator"));

        ToolExposure exposure = planner.plan(ctx);

        assertThat(exposure.tools()).extracting(Tool::name).containsExactly("docs-search");
        assertThat(exposure.requiredToolName()).isNull();
        assertThat(ctx.getAttributes())
            .containsEntry(PipelineContext.ATTR_TOOL_MATCH_MODE, PipelineContext.TOOL_MATCH_KEYWORD);
    }

    @Test
    void modelFallbackExposesAllowedToolsWithoutRequiredTool() {
        ToolRouter router = new ToolRouter();
        router.register(tool("docs-search", "skill", "docs-search@1.0.0", List.of("search"), List.of("搜索")));
        router.register(tool("calculator", "skill", "calculator@1.0.0", List.of("math"), List.of("计算")));
        ToolExposurePlanner planner = new ToolExposurePlanner(router);

        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("随便聊聊");
        ctx.getAttributes().put(PipelineContext.ATTR_AGENT_SKILL_IDS, List.of("docs-search", "calculator"));

        ToolExposure exposure = planner.plan(ctx);

        assertThat(exposure.tools()).extracting(Tool::name)
            .containsExactlyInAnyOrder("docs-search", "calculator");
        assertThat(exposure.requiredToolName()).isNull();
        assertThat(ctx.getAttributes())
            .containsEntry(PipelineContext.ATTR_TOOL_MATCH_MODE, PipelineContext.TOOL_MATCH_MODEL);
    }

    @Test
    void disabledToolExposureHidesToolsForInternalControlCalls() {
        ToolRouter router = new ToolRouter();
        router.register(tool("docs-search", "skill", "docs-search@1.0.0", List.of("search"), List.of("搜索")));
        ToolExposurePlanner planner = new ToolExposurePlanner(router);

        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("请只返回 JSON，不要调用工具");
        ctx.getAttributes().put(PipelineContext.ATTR_AGENT_SKILL_IDS, List.of("docs-search"));
        ctx.getAttributes().put(PipelineContext.ATTR_TOOL_EXPOSURE_DISABLED, true);

        ToolExposure exposure = planner.plan(ctx);

        assertThat(exposure.tools()).isEmpty();
        assertThat(exposure.requiredToolName()).isNull();
        assertThat(ctx.getAttributes())
            .containsEntry(PipelineContext.ATTR_TOOL_MATCH_MODE, PipelineContext.TOOL_MATCH_DISABLED);
    }

    @Test
    void urlTextDoesNotApplyDomainFilteringDuringModelFallback() {
        ToolRouter router = new ToolRouter();
        router.register(tool("fetch_docs_article", "mcp", "docs-fetcher",
            List.of("host:docs.example.com"), List.of()));
        router.register(tool("fetch_nowcoder_java_interview_article", "mcp", "nowcoder-java-interview",
            List.of(), List.of()));
        ToolExposurePlanner planner = new ToolExposurePlanner(router);

        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("读取 https://blog.example.com/p/123");

        ToolExposure exposure = planner.plan(ctx);

        assertThat(exposure.tools()).extracting(Tool::name)
            .containsExactlyInAnyOrder("fetch_docs_article", "fetch_nowcoder_java_interview_article");
        assertThat(exposure.requiredToolName()).isNull();
        assertThat(ctx.getAttributes())
            .containsEntry(PipelineContext.ATTR_TOOL_MATCH_MODE, PipelineContext.TOOL_MATCH_MODEL);
    }

    @Test
    void mcpMetadataCanDeclareDirectResultAndChineseKeywords() {
        ToolRouter router = new ToolRouter();
        ToolProvider provider = new ToolProvider() {
            @Override
            public List<ToolSpec> getTools() {
                return List.of(new ToolSpec(
                    "fetch_nowcoder_java_interview_article",
                    "抓取牛客网 Java 面经文章",
                    Map.of()
                ));
            }

            @Override
            public com.echomind.mcp.ToolResult call(String toolName, Map<String, Object> arguments) {
                return com.echomind.mcp.ToolResult.success("ok");
            }
        };
        router.registerAll(MCPToolProviderAdapter.adapt(provider, "mcp:nowcoder-java-interview", Map.of(
            "fetch_nowcoder_java_interview_article",
            new ExternalMcpToolMetadata(
                List.of("interview"),
                List.of("牛客", "面经"),
                Map.of("interview", List.of("面试经验"))
            )
        )));
        ToolExposurePlanner planner = new ToolExposurePlanner(router);

        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("查一下牛客java面经一篇");

        ToolExposure exposure = planner.plan(ctx);

        assertThat(exposure.tools()).extracting(Tool::name)
            .containsExactly("fetch_nowcoder_java_interview_article");
        assertThat(exposure.requiredToolName()).isNull();
    }

    @Test
    void weatherToolCanBeKeywordMatchedWithoutDirectResult() {
        ToolRouter router = new ToolRouter();
        router.register(tool("weather-query", "skill", "weather-query@1.0.0",
            List.of("weather"), List.of("天气", "温度")));
        ToolExposurePlanner planner = new ToolExposurePlanner(router);

        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("最低温度和最高温度是多少?");

        ToolExposure exposure = planner.plan(ctx);

        assertThat(exposure.tools()).extracting(Tool::name).containsExactly("weather-query");
        assertThat(exposure.requiredToolName()).isNull();
    }

    @Test
    void followUpDateQueryAlsoExposesRailwayToolFromRecentHistory() {
        ToolRouter router = new ToolRouter();
        router.register(tool("12306", "skill", "12306@1.0.0",
            List.of("railway", "ticket"), List.of("火车票", "余票", "高铁票")));
        router.register(tool("date-query", "skill", "date-query@1.0.0",
            List.of("date", "time"), List.of("今天", "明天", "后天", "日期")));
        ToolExposurePlanner planner = new ToolExposurePlanner(router);

        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("查后天的");
        ctx.getMessages().add(AgentMessage.user("查询重庆到临沂的火车票"));
        ctx.getMessages().add(AgentMessage.assistant("需要我帮你查其他日期吗？"));
        ctx.getMessages().add(AgentMessage.user("查后天的"));
        ctx.getAttributes().put(PipelineContext.ATTR_AGENT_SKILL_IDS, List.of("12306", "date-query"));

        ToolExposure exposure = planner.plan(ctx);

        assertThat(exposure.tools()).extracting(Tool::name)
            .containsExactlyInAnyOrder("date-query", "12306");
        assertThat(ctx.getAttributes())
            .containsEntry(PipelineContext.ATTR_CONTEXT_MATCHED_TOOLS, List.of("12306"));
    }

    @Test
    void genericContinueFollowUpExposesRailwayToolFromRecentHistory() {
        ToolRouter router = new ToolRouter();
        router.register(tool("12306", "skill", "12306@1.0.0",
            List.of("railway", "ticket"), List.of("火车票", "余票", "高铁票")));
        router.register(tool("docs-search", "skill", "docs-search@1.0.0",
            List.of("search"), List.of("搜索")));
        ToolExposurePlanner planner = new ToolExposurePlanner(router);

        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("继续查询");
        ctx.getMessages().add(AgentMessage.user("查询重庆到临沂的火车票"));
        ctx.getMessages().add(AgentMessage.assistant("未查到直达车，是否继续看中转？"));
        ctx.getMessages().add(AgentMessage.user("继续查询"));
        ctx.getAttributes().put(PipelineContext.ATTR_AGENT_SKILL_IDS, List.of("12306", "docs-search"));

        ToolExposure exposure = planner.plan(ctx);

        assertThat(exposure.tools()).extracting(Tool::name)
            .containsExactly("12306");
        assertThat(ctx.getAttributes())
            .containsEntry(PipelineContext.ATTR_TOOL_MATCH_MODE, PipelineContext.TOOL_MATCH_KEYWORD);
    }

    @Test
    void dateFollowUpWithoutRailwayHistoryDoesNotExposeRailwayTool() {
        ToolRouter router = new ToolRouter();
        router.register(tool("12306", "skill", "12306@1.0.0",
            List.of("railway", "ticket"), List.of("火车票", "余票", "高铁票")));
        router.register(tool("date-query", "skill", "date-query@1.0.0",
            List.of("date", "time"), List.of("今天", "明天", "后天", "日期")));
        ToolExposurePlanner planner = new ToolExposurePlanner(router);

        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("查后天的");
        ctx.getMessages().add(AgentMessage.user("你好"));
        ctx.getMessages().add(AgentMessage.assistant("你好"));
        ctx.getMessages().add(AgentMessage.user("查后天的"));
        ctx.getAttributes().put(PipelineContext.ATTR_AGENT_SKILL_IDS, List.of("12306", "date-query"));

        ToolExposure exposure = planner.plan(ctx);

        assertThat(exposure.tools()).extracting(Tool::name)
            .containsExactly("date-query");
        assertThat(ctx.getAttributes()).doesNotContainKey(PipelineContext.ATTR_CONTEXT_MATCHED_TOOLS);
    }

    @Test
    void longerNewDateQuestionDoesNotPullRailwayToolFromHistory() {
        ToolRouter router = new ToolRouter();
        router.register(tool("12306", "skill", "12306@1.0.0",
            List.of("railway", "ticket"), List.of("火车票", "余票", "高铁票")));
        router.register(tool("date-query", "skill", "date-query@1.0.0",
            List.of("date", "time"), List.of("今天", "明天", "后天", "日期")));
        router.register(tool("weather-query", "skill", "weather-query@1.0.0",
            List.of("weather"), List.of("天气")));
        ToolExposurePlanner planner = new ToolExposurePlanner(router);

        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("查询明天重庆天气");
        ctx.getMessages().add(AgentMessage.user("查询重庆到临沂的火车票"));
        ctx.getMessages().add(AgentMessage.assistant("已经查到中转方案"));
        ctx.getMessages().add(AgentMessage.user("查询明天重庆天气"));
        ctx.getAttributes().put(PipelineContext.ATTR_AGENT_SKILL_IDS, List.of("12306", "date-query", "weather-query"));

        ToolExposure exposure = planner.plan(ctx);

        assertThat(exposure.tools()).extracting(Tool::name)
            .containsExactlyInAnyOrder("date-query", "weather-query");
        assertThat(ctx.getAttributes()).doesNotContainKey(PipelineContext.ATTR_CONTEXT_MATCHED_TOOLS);
    }

    @Test
    void functionNameNormalizesInvalidLeadingName() {
        ToolExposurePlanner planner = new ToolExposurePlanner(new ToolRouter());

        assertThat(planner.functionName(tool("12306", "skill", "12306@1.0.0", List.of(), List.of())))
            .isEqualTo("tool_12306");
        assertThat(planner.functionName(tool("docs-search", "skill", "docs-search@1.0.0", List.of(), List.of())))
            .isEqualTo("docs_search");
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
