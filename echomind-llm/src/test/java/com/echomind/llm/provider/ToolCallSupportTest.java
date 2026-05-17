package com.echomind.llm.provider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallSupportTest {

    private final LlmTool webSearch = new LlmTool(
        "web_search",
        "Search the web",
        Map.of(
            "type", "object",
            "properties", Map.of("query", Map.of("type", "string")),
            "required", List.of("query")
        ),
        ignored -> "ok"
    );

    @Test
    void detectsPureToolNameOnly() {
        assertThat(ToolCallSupport.detectDegradedToolSelection("web-search", List.of(webSearch)))
            .isEqualTo(webSearch);
        assertThat(ToolCallSupport.detectDegradedToolSelection("web_search", List.of(webSearch)))
            .isEqualTo(webSearch);
    }

    @Test
    void ignoresOrdinarySentenceContainingToolName() {
        assertThat(ToolCallSupport.detectDegradedToolSelection(
            "我准备使用 web-search 查询一下", List.of(webSearch)))
            .isNull();
    }

    @Test
    void fillsQueryFromUrlInput() {
        assertThat(ToolCallSupport.defaultArguments(webSearch, "https://deepseek.com/"))
            .containsEntry("query", "https://deepseek.com/");
    }

    @Test
    void stripsLeadingSearchWordsForQuery() {
        assertThat(ToolCallSupport.defaultArguments(webSearch, "查询 https://api.example.com/keys"))
            .containsEntry("query", "https://api.example.com/keys");
        assertThat(ToolCallSupport.defaultArguments(webSearch, "搜索 deepseek 最新消息"))
            .containsEntry("query", "deepseek 最新消息");
    }

    @Test
    void doesNotCopyUserTextIntoEveryOptionalNowcoderParameter() {
        LlmTool nowcoder = new LlmTool(
            "fetch_nowcoder_java_interview_article",
            "抓取牛客网 Java 面经文章",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "url", Map.of("type", "string"),
                    "random", Map.of("type", "boolean"),
                    "keyword", Map.of("type", "string"),
                    "maxAttempts", Map.of("type", "integer"),
                    "includeHtml", Map.of("type", "boolean")
                )
            ),
            ignored -> "ok"
        );

        assertThat(ToolCallSupport.defaultArguments(nowcoder, "帮我抓取一篇java面经"))
            .containsEntry("random", true)
            .containsEntry("keyword", "Java")
            .doesNotContainKeys("url", "maxAttempts", "includeHtml");
    }

    @Test
    void dateQueryUsesEmptyArgumentsForCurrentDateQuestion() {
        LlmTool dateQuery = new LlmTool(
            "date_query",
            "Query current date",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "date", Map.of("type", "string"),
                    "zoneId", Map.of("type", "string"),
                    "offsetDays", Map.of("type", "integer"),
                    "format", Map.of("type", "string"),
                    "locale", Map.of("type", "string")
                )
            ),
            ignored -> "ok"
        );

        assertThat(ToolCallSupport.defaultArguments(dateQuery, "今天日期是多少")).isEmpty();
        assertThat(ToolCallSupport.defaultArguments(dateQuery, "明天星期几"))
            .containsEntry("offsetDays", 1)
            .doesNotContainKeys("date", "zoneId", "format");
    }
}
