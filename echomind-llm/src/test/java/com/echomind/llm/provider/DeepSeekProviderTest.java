package com.echomind.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelSpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void extractsTextAndIgnoresThinkingBlocks() throws Exception {
        DeepSeekProvider provider = new DeepSeekProvider("https://api.deepseek.com/anthropic", "test-key");

        String text = provider.extractText(mapper.readTree("""
            {
              "content": [
                {"type": "thinking", "thinking": "internal reasoning"},
                {"type": "text", "text": "最终答案"}
              ]
            }
            """));

        assertThat(text).isEqualTo("最终答案");
    }

    @Test
    void requestBodyDoesNotUseSystemRole() {
        DeepSeekProvider provider = new DeepSeekProvider("https://api.deepseek.com/anthropic", "test-key");

        Map<String, Object> body = provider.buildRequestBody(
            new ModelSpec("deepseek", "deepseek-v4-flash", Set.of(ModelCapability.TEXT), true),
            "你是助手",
            "你好"
        );

        assertThat(body).containsEntry("model", "deepseek-v4-flash");
        assertThat(body.toString()).doesNotContain("role=system");
        assertThat(body.toString()).contains("你是助手");
        assertThat(body.toString()).contains("你好");
    }

    @Test
    void requestBodyCanExposeAnthropicStyleTools() {
        DeepSeekProvider provider = new DeepSeekProvider("https://api.deepseek.com/anthropic", "test-key");

        Map<String, Object> body = provider.buildRequestBody(
            new ModelSpec("deepseek", "deepseek-v4-flash", Set.of(ModelCapability.TEXT), true),
            "你是助手",
            "今天几号",
            List.of(new LlmTool(
                "date_query",
                "Query date",
                Map.of("properties", Map.of("zoneId", Map.of("type", "string"))),
                ignored -> "日期：2026-05-13"
            ))
        );

        assertThat(body).containsKey("tools");
        assertThat(body).containsKey("tool_choice");
        assertThat(body.toString()).contains("input_schema");
        assertThat(body.toString()).contains("date_query");
        assertThat(body.toString()).contains("type=object");
    }

    @Test
    void requiredToolNameUsesAnthropicRequiredToolChoice() {
        DeepSeekProvider provider = new DeepSeekProvider("https://api.deepseek.com/anthropic", "test-key");

        Map<String, Object> body = provider.buildRequestBody(
            new ModelSpec("deepseek", "deepseek-v4-flash", Set.of(ModelCapability.TEXT), true),
            "你是助手",
            "搜一下https://example.com",
            List.of(new LlmTool(
                "web_search",
                "Search the web",
                Map.of("properties", Map.of("query", Map.of("type", "string"))),
                ignored -> "搜索结果"
            )),
            "web_search"
        );

        assertThat(body).containsEntry("tool_choice", Map.of("type", "tool", "name", "web_search"));
    }

    @Test
    void parsesDeepSeekDsmlToolCallsFromTextBlocks() {
        DeepSeekProvider provider = new DeepSeekProvider("https://api.deepseek.com/anthropic", "test-key");

        List<DeepSeekProvider.DsmlToolCall> calls = provider.dsmlToolCalls("""
            <｜｜DSML｜｜tool_calls>
            <｜｜DSML｜｜invoke name="web_search">
            <｜｜DSML｜｜parameter name="query" string="true">杭州 60人 户外团建 场地</｜｜DSML｜｜parameter>
            </｜｜DSML｜｜invoke>
            <｜｜DSML｜｜invoke name="calculate">
            <｜｜DSML｜｜parameter name="expression" string="true">60 / 6</｜｜DSML｜｜parameter>
            </｜｜DSML｜｜invoke>
            </｜｜DSML｜｜tool_calls>
            """);

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).name()).isEqualTo("web_search");
        assertThat(calls.get(0).arguments()).containsEntry("query", "杭州 60人 户外团建 场地");
        assertThat(calls.get(1).name()).isEqualTo("calculate");
        assertThat(calls.get(1).arguments()).containsEntry("expression", "60 / 6");
    }

    @Test
    void fallbackToolDeliverableReturnsReviewableContentInsteadOfError() {
        DeepSeekProvider provider = new DeepSeekProvider("https://api.deepseek.com/anthropic", "test-key");

        String fallback = provider.fallbackToolDeliverable(new StringBuilder("""
            Tool: web_search -> web_search
            Arguments: {"query":"北京户外团建"}
            Result:
            Web search results for "北京户外团建": 场地A、场地B
            """));

        assertThat(fallback).doesNotStartWith("[Error]");
        assertThat(fallback).contains("模型持续请求更多工具调用");
        assertThat(fallback).contains("场地A、场地B");
    }

    @Test
    void requestBodyIncludesStrongToolUseInstruction() {
        DeepSeekProvider provider = new DeepSeekProvider("https://api.deepseek.com/anthropic", "test-key");

        Map<String, Object> body = provider.buildRequestBody(
            new ModelSpec("deepseek", "deepseek-v4-flash", Set.of(ModelCapability.TEXT), true),
            "你是助手",
            "查询 https://deepseek.com/",
            List.of(new LlmTool(
                "web_search",
                "Search the web",
                Map.of("properties", Map.of("query", Map.of("type", "string"))),
                ignored -> "搜索结果"
            ))
        );

        assertThat(body.toString()).contains("must emit a formal tool call");
        assertThat(body.toString()).contains("web_search");
        assertThat(body.toString()).contains("query");
    }

    @Test
    void deepSeekStreamReadsAnthropicTextDeltas() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/anthropic/messages", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] body = """
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}

                event: message_stop
                data: {"type":"message_stop"}

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            DeepSeekProvider provider = new DeepSeekProvider(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/anthropic",
                "test-key"
            );

            List<String> tokens = provider.stream(new ProviderRequest(
                    new ModelSpec("deepseek", "deepseek-v4-flash", Set.of(ModelCapability.TEXT), true),
                    "你是助手",
                    "你好",
                    List.of(),
                    List.of()
                ))
                .collectList()
                .block(Duration.ofSeconds(3));

            assertThat(tokens).containsExactly("你", "好");
            assertThat(requestBody.get()).contains("\"stream\":true");
            assertThat(apiKey.get()).isEqualTo("test-key");
        } finally {
            server.stop(0);
        }
    }

}
