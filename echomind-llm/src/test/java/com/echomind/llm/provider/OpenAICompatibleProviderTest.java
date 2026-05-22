package com.echomind.llm.provider;

import com.echomind.common.model.TokenUsage;
import com.echomind.llm.provider.SpringAiProviderTestSupport.CapturingChatModel;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.dto.ProviderResponse;
import com.echomind.llm.provider.tool.LlmTool;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.echomind.llm.provider.SpringAiProviderTestSupport.response;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAICompatibleProviderTest {

    @Test
    void chatWithUsageMapsSpringAiResponseAndOptions() {
        CapturingChatModel chatModel = new CapturingChatModel(prompt -> response("你好", TokenUsage.of(10, 2, 12)));
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
            "aliyun-bailian", "https://dashscope.aliyuncs.com/compatible-mode/v1", "test-key", chatModel);

        ProviderResponse response = provider.chatWithUsage(new ProviderRequest(
            new ModelSpec("aliyun-bailian", "qwen3.7-max", Set.of(ModelCapability.TEXT), true),
            "你是助手",
            "你好",
            List.of(),
            List.of()
        ));

        assertThat(response.content()).isEqualTo("你好");
        assertThat(response.usage().totalTokens()).isEqualTo(12);
        assertThat(chatModel.lastPrompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
        assertThat(((OpenAiChatOptions) chatModel.lastPrompt.getOptions()).getModel()).isEqualTo("qwen3.7-max");
    }

    @Test
    void streamWithUsageMapsSpringAiChunks() {
        CapturingChatModel chatModel = new CapturingChatModel(prompt -> response("", null));
        chatModel.streamHandler = prompt -> Flux.just(
            response("你", null),
            response("好", null),
            response("", TokenUsage.of(10, 2, 12))
        );
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
            "aliyun-bailian", "https://dashscope.aliyuncs.com/compatible-mode/v1", "test-key", chatModel);

        List<ProviderStreamChunk> chunks = provider.streamWithUsage(new ProviderRequest(
                new ModelSpec("aliyun-bailian", "qwen3.7-max", Set.of(ModelCapability.TEXT), true),
                "你是助手",
                "你好",
                List.of(),
                List.of()
            ))
            .collectList()
            .block();

        assertThat(chunks).isNotNull();
        assertThat(chunks.stream().filter(ProviderStreamChunk::hasContent).map(ProviderStreamChunk::content).toList())
            .containsExactly("你", "好");
        assertThat(chunks.stream().filter(ProviderStreamChunk::hasUsage).findFirst().orElseThrow()
            .usage().totalTokens()).isEqualTo(12);
    }

    @Test
    void toolCallbacksKeepSchemaRequiredToolAndDirectReturnMetadata() {
        AtomicReference<String> toolInput = new AtomicReference<>();
        CapturingChatModel chatModel = new CapturingChatModel(prompt -> {
            OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
            ToolCallback callback = options.getToolCallbacks().get(0);
            assertThat(options.getToolChoice()).isNotNull();
            assertThat(callback.getToolDefinition().name()).isEqualTo("web_search");
            assertThat(callback.getToolDefinition().inputSchema()).contains("\"query\"");
            assertThat(callback.getToolMetadata().returnDirect()).isTrue();
            String result = callback.call("{\"query\":\"JVM\"}");
            toolInput.set("{\"query\":\"JVM\"}");
            return response(result, TokenUsage.of(8, 1, 9));
        });
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
            "aliyun-bailian", "https://dashscope.aliyuncs.com/compatible-mode/v1", "test-key", chatModel);

        ProviderResponse response = provider.chatWithUsage(new ProviderRequest(
            new ModelSpec("aliyun-bailian", "qwen3.7-max", Set.of(ModelCapability.TEXT, ModelCapability.FUNCTION), true),
            "你是助手",
            "搜一下 JVM",
            List.of(),
            List.of(new LlmTool(
                "web_search",
                "Search the web",
                Map.of(
                    "type", "object",
                    "properties", Map.of("query", Map.of("type", "string")),
                    "required", List.of("query")
                ),
                true,
                ignored -> "Fetched web page: JVM article"
            )),
            "web_search"
        ));

        assertThat(response.content()).isEqualTo("Fetched web page: JVM article");
        assertThat(toolInput.get()).isEqualTo("{\"query\":\"JVM\"}");
    }

    @Test
    void stopsRunawayInternalToolCalling() {
        AtomicInteger executedCalls = new AtomicInteger();
        CapturingChatModel chatModel = new CapturingChatModel(prompt -> {
            OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
            ToolCallback callback = options.getToolCallbacks().get(0);
            for (int i = 0; i < 9; i++) {
                callback.call("{\"query\":\"EchoMind\"}");
            }
            return response("不会走到这里", null);
        });
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
            "aliyun-bailian", "https://dashscope.aliyuncs.com/compatible-mode/v1", "test-key", chatModel);

        ProviderResponse response = provider.chatWithUsage(new ProviderRequest(
            new ModelSpec("aliyun-bailian", "qwen3.7-max", Set.of(ModelCapability.TEXT, ModelCapability.FUNCTION), true),
            "你是助手",
            "谁给你定的规则",
            List.of(),
            List.of(new LlmTool(
                "github_intel",
                "Search GitHub",
                Map.of(
                    "type", "object",
                    "properties", Map.of("query", Map.of("type", "string")),
                    "required", List.of("query")
                ),
                false,
                ignored -> {
                    executedCalls.incrementAndGet();
                    return "not found";
                }
            ))
        ));

        assertThat(response.failed()).isTrue();
        assertThat(response.failureReason()).contains("工具调用次数超过上限");
        assertThat(executedCalls.get()).isEqualTo(8);
    }

    @Test
    void returnsFailureWhenNotConfigured() {
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
            "aliyun-bailian", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", new CapturingChatModel(prompt -> response("ignored", null)));

        ProviderResponse response = provider.chatWithUsage(new ProviderRequest(
            new ModelSpec("aliyun-bailian", "qwen3.7-max", Set.of(ModelCapability.TEXT), true),
            "你是助手",
            "你好",
            List.of(),
            List.of()
        ));

        assertThat(response.failed()).isTrue();
        assertThat(response.failureReason()).contains("not configured");
    }

    @Test
    void returnsFailureWhenSpringAiCallThrows() {
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
            "aliyun-bailian", "https://dashscope.aliyuncs.com/compatible-mode/v1", "test-key",
            new CapturingChatModel(prompt -> {
                throw new IllegalStateException("upstream unavailable");
            }));

        ProviderResponse response = provider.chatWithUsage(new ProviderRequest(
            new ModelSpec("aliyun-bailian", "qwen3.7-max", Set.of(ModelCapability.TEXT), true),
            "你是助手",
            "你好",
            List.of(),
            List.of()
        ));

        assertThat(response.failed()).isTrue();
        assertThat(response.failureReason()).contains("upstream unavailable");
    }
}
