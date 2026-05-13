package com.echomind.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具路由测试。
 *
 * <p>工具调用策略是“先关键词匹配，再交给模型智能判断”。这里锁住关键词候选集，
 * 确保模型阶段只看到当前Agent允许且与用户消息相关的工具。</p>
 */
class ToolRouterTest {

    @Test
    void matchForAgentSkillIdsReturnsOnlyKeywordMatchedAllowedTools() {
        ToolRouter router = new ToolRouter();
        router.register(tool("calculator", "skill", "calculator@1.0.0", List.of("calculate", "math")));
        router.register(tool("web-search", "skill", "web-search@1.0.0", List.of("search", "web")));
        router.register(tool("date-query", "skill", "date-query@1.0.0", List.of("date", "time", "weekday")));

        List<Tool> matched = router.matchForAgentSkillIds(
            "帮我计算一下 1+2",
            List.of("calculator", "web-search")
        );

        assertThat(matched).extracting(Tool::name).containsExactly("calculator");
    }

    @Test
    void matchForAgentSkillIdsDoesNotExposeDisallowedSkillEvenWhenKeywordMatches() {
        ToolRouter router = new ToolRouter();
        router.register(tool("weather-query", "skill", "weather-query@1.0.0", List.of("weather", "forecast")));
        router.register(tool("calculator", "skill", "calculator@1.0.0", List.of("calculate", "math")));

        List<Tool> matched = router.matchForAgentSkillIds(
            "查询一下杭州天气",
            List.of("calculator")
        );

        assertThat(matched).isEmpty();
    }

    @Test
    void listForAgentSkillIdsStillProvidesFallbackToolsWhenKeywordsMiss() {
        ToolRouter router = new ToolRouter();
        router.register(tool("calculator", "skill", "calculator@1.0.0", List.of("calculate", "math")));
        router.register(tool("web-search", "skill", "web-search@1.0.0", List.of("search", "web")));

        assertThat(router.matchForAgentSkillIds("随便聊聊", List.of("calculator", "web-search"))).isEmpty();
        assertThat(router.listForAgentSkillIds(List.of("calculator", "web-search")))
            .extracting(Tool::name)
            .containsExactlyInAnyOrder("calculator", "web-search");
    }

    @Test
    void explicitKeywordsLetNewJarMatchWithoutPlatformAliasChange() {
        ToolRouter router = new ToolRouter();
        router.register(tool(
            "invoice-auditor",
            "skill",
            "invoice-auditor@1.0.0",
            List.of("finance"),
            List.of("发票审核", "报销", "invoice audit"),
            Map.of("invoice", List.of("发票", "票据"))
        ));
        router.register(tool("calculator", "skill", "calculator@1.0.0", List.of("calculate", "math")));

        List<Tool> matched = router.matchForAgentSkillIds(
            "帮我做一下发票审核",
            List.of("invoice-auditor", "calculator")
        );

        assertThat(matched).extracting(Tool::name).containsExactly("invoice-auditor");
    }

    @Test
    void weakDescriptionHitDoesNotNarrowCandidatesBeforeModelFallback() {
        ToolRouter router = new ToolRouter();
        router.register(tool(
            "contract-review",
            "skill",
            "contract-review@1.0.0",
            List.of("legal"),
            List.of(),
            Map.of(),
            "Process user text and return useful result"
        ));

        List<Tool> matched = router.matchForAgentSkillIds(
            "帮我处理一下这个文本",
            List.of("contract-review")
        );

        assertThat(matched).isEmpty();
        assertThat(router.listForAgentSkillIds(List.of("contract-review")))
            .extracting(Tool::name)
            .containsExactly("contract-review");
    }

    private Tool tool(String name, String sourceType, String sourceId, List<String> tags) {
        return tool(name, sourceType, sourceId, tags, List.of(), Map.of());
    }

    private Tool tool(String name, String sourceType, String sourceId, List<String> tags,
                      List<String> keywords, Map<String, List<String>> aliases) {
        return tool(name, sourceType, sourceId, tags, keywords, aliases, name + " test tool");
    }

    private Tool tool(String name, String sourceType, String sourceId, List<String> tags,
                      List<String> keywords, Map<String, List<String>> aliases, String description) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
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
            public Map<String, List<String>> aliases() {
                return aliases;
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
