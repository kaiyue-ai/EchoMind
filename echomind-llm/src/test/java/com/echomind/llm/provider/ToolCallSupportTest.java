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
}
