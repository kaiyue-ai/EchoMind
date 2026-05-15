package com.echomind.llm.provider;

import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelSpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAICompatibleProviderTest {

    @Test
    void toolPromptIsInjectedIntoSystemMessage() throws Exception {
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
            "openai-compatible", "https://example.com", "test-key");

        Method method = OpenAICompatibleProvider.class.getDeclaredMethod(
            "userMessage", String.class, List.class);
        method.setAccessible(true);
        assertThat(method.invoke(provider, "hello", List.of())).isNotNull();

        Method toolMethod = OpenAICompatibleProvider.class.getDeclaredMethod(
            "callChatCompletions",
            ModelSpec.class, String.class, String.class, List.class, List.class);
        toolMethod.setAccessible(true);

        try {
            toolMethod.invoke(
                provider,
                new ModelSpec("openai-compatible", "gpt", Set.of(ModelCapability.TEXT), true),
                "你是助手",
                "查询 https://deepseek.com/",
                List.of(),
                List.of(new LlmTool(
                    "web_search",
                    "Search the web",
                    Map.of("properties", Map.of("query", Map.of("type", "string"))),
                    ignored -> "ok"
                ))
            );
        } catch (Exception ignored) {
            // Request will fail without a live endpoint; this test only checks helper behavior below.
        }

        assertThat(ToolCallSupport.toolUseInstruction(
            "查询 https://deepseek.com/",
            List.of(new LlmTool("web_search", "Search the web", Map.of(), ignored -> "ok"))
        )).contains("must emit a formal tool call");
    }

    @Test
    void degradedToolSelectionUsesDefaultQueryExtraction() {
        LlmTool tool = new LlmTool(
            "web_search",
            "Search the web",
            Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query")
            ),
            ignored -> "ok"
        );

        assertThat(ToolCallSupport.detectDegradedToolSelection("web-search", List.of(tool)))
            .isEqualTo(tool);
        assertThat(ToolCallSupport.defaultArguments(tool, "查询 https://api.example.com/keys"))
            .containsEntry("query", "https://api.example.com/keys");
    }

    @Test
    void openAiCompatibleStreamReadsSseDeltas() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                data: {"choices":[{"delta":{"content":"你"}}]}

                data: {"choices":[{"delta":{"content":"好"}}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                "aliyun-bailian",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key"
            );

            List<String> tokens = provider.stream(new ProviderRequest(
                    new ModelSpec("aliyun-bailian", "qwen-plus", Set.of(ModelCapability.TEXT), true),
                    "你是助手",
                    "你好",
                    List.of(),
                    List.of()
                ))
                .collectList()
                .block(Duration.ofSeconds(3));

            assertThat(tokens).containsExactly("你", "好");
            assertThat(requestBody.get()).contains("\"stream\":true");
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamWithToolsContinuesAfterToolCallUsingNonStreamingRequest() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        List<String> requestBodies = new ArrayList<>();
        AtomicReference<String> toolInput = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int count = requestCount.incrementAndGet();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(requestBody);
            if (count == 1) {
                byte[] body = """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"calculator","arguments":"{\\"expression\\":"}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"2+2\\"}"}}]}}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                byte[] body = """
                    {"choices":[{"message":{"content":"4"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();

        try {
            OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                "aliyun-bailian",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key"
            );
            LlmTool calculator = new LlmTool(
                "calculator",
                "Evaluate math",
                Map.of(
                    "type", "object",
                    "properties", Map.of("expression", Map.of("type", "string")),
                    "required", List.of("expression")
                ),
                input -> {
                    toolInput.set(input);
                    return "4";
                }
            );

            List<String> tokens = provider.stream(new ProviderRequest(
                    new ModelSpec("aliyun-bailian", "qwen-plus", Set.of(ModelCapability.TEXT), true),
                    "你是助手",
                    "计算 2+2",
                    List.of(),
                    List.of(calculator)
                ))
                .collectList()
                .block(Duration.ofSeconds(3));

            assertThat(tokens).containsExactly("4");
            assertThat(requestCount.get()).isEqualTo(2);
            assertThat(requestBodies.get(0)).contains("\"stream\":true", "\"tools\"");
            assertThat(requestBodies.get(1)).doesNotContain("\"stream\":true");
            assertThat(requestBodies.get(1)).contains("\"role\":\"tool\"", "\"tool_call_id\":\"call_1\"");
            assertThat(toolInput.get()).isEqualTo("{\"expression\":\"2+2\"}");
        } finally {
            server.stop(0);
        }
    }
}
