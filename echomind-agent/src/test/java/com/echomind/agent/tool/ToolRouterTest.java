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
 * 工具路由测试。
 *
 * <p>工具调用策略是“先关键词匹配，再交给模型智能判断”。这里锁住关键词候选集，
 * 确保模型阶段只看到当前Agent允许且与用户消息相关的工具。</p>
 */
class ToolRouterTest {

    @Test
    void matchForAgentSkillIdsReturnsOnlyKeywordMatchedAllowedTools() {
        ToolRouter router = new ToolRouter();
        router.register(tool("calculator", "skill", "calculator@1.0.0",
            List.of("calculate", "math"), List.of("计算", "算"), Map.of()));
        router.register(tool("docs-search", "skill", "docs-search@1.0.0", List.of("search", "docs")));
        router.register(tool("date-query", "skill", "date-query@1.0.0", List.of("date", "time", "weekday")));

        List<Tool> matched = router.matchForAgentSkillIds(
            "帮我计算一下 1+2",
            List.of("calculator", "docs-search")
        );

        assertThat(matched).extracting(Tool::name).containsExactly("calculator");
    }

    @Test
    void matchForAgentSkillIdsDoesNotExposeDisallowedSkillEvenWhenKeywordMatches() {
        ToolRouter router = new ToolRouter();
        router.register(tool("weather-query", "skill", "weather-query@1.0.0",
            List.of("weather", "forecast"), List.of("天气"), Map.of()));
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
        router.register(tool("docs-search", "skill", "docs-search@1.0.0", List.of("search", "docs")));

        assertThat(router.matchForAgentSkillIds("随便聊聊", List.of("calculator", "docs-search"))).isEmpty();
        assertThat(router.listForAgentSkillIds(List.of("calculator", "docs-search")))
            .extracting(Tool::name)
            .containsExactlyInAnyOrder("calculator", "docs-search");
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
    void chineseExplicitKeywordCanBeSingleCharacterForCommonUserPhrases() {
        ToolRouter router = new ToolRouter();
        router.register(tool("calculator", "skill", "calculator@1.0.0",
            List.of("calculate", "math"), List.of("算"), Map.of()));

        List<Tool> matched = router.matchForAgentSkillIds(
            "帮我算 1+2",
            List.of("calculator")
        );

        assertThat(matched).extracting(Tool::name).containsExactly("calculator");
    }

    @Test
    void chineseKeywordMatchesAcrossFullWidthPunctuationAndSpaces() {
        ToolRouter router = new ToolRouter();
        router.register(tool(
            "invoice-auditor",
            "skill",
            "invoice-auditor@1.0.0",
            List.of("finance"),
            List.of("发票审核"),
            Map.of()
        ));

        List<Tool> matched = router.matchForAgentSkillIds(
            "麻烦做一下：发 票-审 核",
            List.of("invoice-auditor")
        );

        assertThat(matched).extracting(Tool::name).containsExactly("invoice-auditor");
    }

    @Test
    void chineseUsersStillNeedChineseKeywordsInsteadOfEnglishOnlyTags() {
        ToolRouter router = new ToolRouter();
        router.register(tool("weather-query", "skill", "weather-query@1.0.0",
            List.of("weather", "forecast")));

        List<Tool> matched = router.matchForAgentSkillIds(
            "查询一下杭州天气",
            List.of("weather-query")
        );

        assertThat(matched).isEmpty();
    }

    @Test
    void singleCharacterChineseMetadataDoesNotTriggerBroadMatches() {
        ToolRouter router = new ToolRouter();
        router.register(tool("lookup-tool", "skill", "lookup-tool@1.0.0",
            List.of("查")));

        List<Tool> matched = router.matchForAgentSkillIds(
            "查一下这个问题",
            List.of("lookup-tool")
        );

        assertThat(matched).isEmpty();
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

    @Test
    void urlRequestStronglyMatchesGenericWebSearch() {
        ToolRouter router = new ToolRouter();
        router.register(tool(
            "open_web_search",
            "skill",
            "open-websearch-mcp",
            List.of("search", "web"),
            List.of("搜索", "搜一下", "链接", "网址", "http", "https"),
            Map.of()
        ));
        router.register(tool("calculator", "skill", "calculator@1.0.0", List.of("calculate", "math")));

        List<Tool> matched = router.matchForAgentSkillIds(
            "搜一下https://blog.csdn.net/2503_93062705/article/details/160631994",
            List.of("open_web_search", "calculator")
        );

        assertThat(matched).extracting(Tool::name).containsExactly("open_web_search");
    }

    @Test
    void travelSkillMatchesThroughJarKeywordsAndRespectsAgentAllowList() {
        ToolRouter router = new ToolRouter();
        router.register(tool(
            "12306",
            "skill",
            "12306@1.0.0",
            List.of("12306", "train", "railway", "ticket"),
            List.of("12306", "火车票", "高铁票", "动车", "列车", "余票", "车次", "站点"),
            Map.of("ticket", List.of("余票", "车票", "火车票", "高铁票"))
        ));
        router.register(tool(
            "travel-planning",
            "skill",
            "travel-planning@1.0.0",
            List.of("travel", "planning", "route", "budget", "packing", "visa"),
            List.of("旅行规划", "行程规划", "旅游路线", "多城市路线", "预算优化", "打包清单", "签证时间表"),
            Map.of("route", List.of("路线", "行程", "多城市", "动线"))
        ));

        assertThat(router.matchForAgentSkillIds(
            "查一下明天北京到上海的高铁余票",
            List.of("12306", "travel-planning")
        )).extracting(Tool::name).containsExactly("12306");

        assertThat(router.matchForAgentSkillIds(
            "帮我规划东京京都大阪 7 天旅行预算和打包清单",
            List.of("12306", "travel-planning")
        )).extracting(Tool::name).containsExactly("travel-planning");

        assertThat(router.matchForAgentSkillIds(
            "查一下明天北京到上海的高铁余票",
            List.of("travel-planning")
        )).isEmpty();
    }

    @Test
    void railwayTicketIntentExposesAllMetadataMatchedToolsWithoutPlatformDisambiguation() {
        ToolRouter router = new ToolRouter();
        router.register(tool(
            "12306",
            "skill",
            "12306@1.0.0",
            List.of("12306", "train", "railway", "ticket"),
            List.of("12306", "火车票", "高铁票", "动车", "列车", "余票", "车次", "站点"),
            Map.of("ticket", List.of("余票", "车票", "火车票", "高铁票"))
        ));
        router.register(tool(
            "date-query",
            "skill",
            "date-query@1.0.0",
            List.of("date", "time", "weekday"),
            List.of("日期", "时间", "今天", "明天", "昨天", "星期", "几号"),
            Map.of("date", List.of("日期", "几号", "今天", "明天", "昨天"))
        ));

        List<Tool> matched = router.matchForAgentSkillIds(
            "查询明天重庆到成都的火车余票",
            List.of("12306", "date-query")
        );

        assertThat(matched).extracting(Tool::name)
            .containsExactlyInAnyOrder("12306", "date-query");
    }

    @Test
    void currentDaySearchIntentKeepsDateQueryVisibleWithWebSearch() {
        ToolRouter router = new ToolRouter();
        router.register(tool(
            "date-query",
            "skill",
            "date-query@1.0.0",
            List.of("date", "time", "weekday"),
            List.of("日期", "时间", "今天", "今日", "当日", "当天", "星期", "几号"),
            Map.of("date", List.of("日期", "几号", "今天", "今日", "当日", "当天"))
        ));
        router.register(tool(
            "open_web_search",
            "mcp",
            "mcp:open-websearch",
            List.of("search", "web", "current"),
            List.of("搜索", "搜一下", "查询", "查一下", "最新", "实时信息"),
            Map.of("search", List.of("搜索", "查一下", "查询"))
        ));

        List<Tool> matched = router.matchForAgentSkillIds(
            "查询一下今日NBA的战况",
            List.of("date-query")
        );

        assertThat(matched).extracting(Tool::name)
            .containsExactlyInAnyOrder("date-query", "open_web_search");
    }

    @Test
    void railwayClassificationFollowUpWithoutStrongMetadataMatchDoesNotExposeTools() {
        ToolRouter router = new ToolRouter();
        router.register(tool(
            "12306",
            "skill",
            "12306@1.0.0",
            List.of("12306", "train", "railway", "ticket"),
            List.of("12306", "火车票", "高铁票", "动车", "列车", "余票", "车次", "站点"),
            Map.of("ticket", List.of("余票", "车票", "火车票", "高铁票"))
        ));
        router.register(tool(
            "docs-search",
            "skill",
            "docs-search@1.1.0",
            List.of("search", "docs"),
            List.of("搜索", "网页", "search"),
            Map.of()
        ));

        assertThat(router.matchForAgentSkillIds(
            "我说这是高铁还是火车？",
            List.of("12306", "docs-search")
        )).isEmpty();
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
