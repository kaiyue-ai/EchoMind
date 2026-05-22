package com.echomind.llm.provider;

import com.echomind.common.model.MessageAttachment;
import com.echomind.common.model.TokenUsage;
import com.echomind.llm.provider.SpringAiProviderTestSupport.CapturingChatModel;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.dto.ProviderResponse;
import com.echomind.llm.provider.tool.LlmTool;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.echomind.llm.provider.SpringAiProviderTestSupport.response;
import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekProviderTest {

    @Test
    void normalizesLegacyAnthropicBaseUrl() {
        assertThat(DeepSeekProvider.normalizeBaseUrl("https://api.deepseek.com/anthropic/"))
            .isEqualTo("https://api.deepseek.com");
        assertThat(DeepSeekProvider.isLegacyAnthropicBaseUrl("https://api.deepseek.com/anthropic"))
            .isTrue();
    }

    @Test
    void chatWithUsageMapsSpringAiResponseAndDeepSeekOptions() {
        CapturingChatModel chatModel = new CapturingChatModel(prompt -> response("最终答案", TokenUsage.of(21, 8, 29)));
        DeepSeekProvider provider = new DeepSeekProvider(
            "https://api.deepseek.com/anthropic", "test-key", 2048, chatModel);

        ProviderResponse response = provider.chatWithUsage(new ProviderRequest(
            new ModelSpec("deepseek", "deepseek-v4-flash", Set.of(ModelCapability.TEXT), true),
            "你是助手",
            "你好",
            List.of(),
            List.of()
        ));

        assertThat(response.content()).isEqualTo("最终答案");
        assertThat(response.usage().totalTokens()).isEqualTo(29);
        assertThat(chatModel.lastPrompt.getOptions()).isInstanceOf(DeepSeekChatOptions.class);
        DeepSeekChatOptions options = (DeepSeekChatOptions) chatModel.lastPrompt.getOptions();
        assertThat(options.getModel()).isEqualTo("deepseek-v4-flash");
        assertThat(options.getMaxTokens()).isEqualTo(2048);
    }

    @Test
    void rejectsImageAttachmentsBeforeCallingModel() {
        CapturingChatModel chatModel = new CapturingChatModel(prompt -> response("ignored", null));
        DeepSeekProvider provider = new DeepSeekProvider("https://api.deepseek.com", "test-key", 4096, chatModel);

        ProviderResponse response = provider.chatWithUsage(new ProviderRequest(
            new ModelSpec("deepseek", "deepseek-v4-flash", Set.of(ModelCapability.TEXT), true),
            "你是助手",
            "看看图片",
            List.of(MessageAttachment.image("local://a.png", "http://localhost/a.png", "image/png", "a.png", 1L)),
            List.of()
        ));

        assertThat(response.failed()).isTrue();
        assertThat(response.failureReason()).contains("多模态");
        assertThat(chatModel.lastPrompt).isNull();
    }

    @Test
    void toolCallbacksKeepRequiredToolAndDirectReturnMetadata() {
        CapturingChatModel chatModel = new CapturingChatModel(prompt -> {
            DeepSeekChatOptions options = (DeepSeekChatOptions) prompt.getOptions();
            ToolCallback callback = options.getToolCallbacks().get(0);
            assertThat(options.getToolChoice()).isNotNull();
            assertThat(callback.getToolDefinition().name()).isEqualTo("tool_12306");
            assertThat(callback.getToolMetadata().returnDirect()).isTrue();
            return response(callback.call("{\"from\":\"重庆\",\"to\":\"临沂\"}"), TokenUsage.of(20, 3, 23));
        });
        DeepSeekProvider provider = new DeepSeekProvider("https://api.deepseek.com", "test-key", 4096, chatModel);

        ProviderResponse response = provider.chatWithUsage(new ProviderRequest(
            new ModelSpec("deepseek", "deepseek-v4-flash", Set.of(ModelCapability.TEXT, ModelCapability.FUNCTION), true),
            "你是助手",
            "查火车票",
            List.of(),
            List.of(new LlmTool(
                "tool_12306",
                "Railway",
                Map.of("type", "object", "properties", Map.of(
                    "from", Map.of("type", "string"),
                    "to", Map.of("type", "string")
                )),
                true,
                ignored -> "12306 余票查询"
            )),
            "tool_12306"
        ));

        assertThat(response.content()).isEqualTo("12306 余票查询");
        assertThat(response.usage().totalTokens()).isEqualTo(23);
    }

    @Test
    void streamWithUsageMapsSpringAiChunks() {
        CapturingChatModel chatModel = new CapturingChatModel(prompt -> response("", null));
        chatModel.streamHandler = prompt -> Flux.just(
            response("最", null),
            response("终", null),
            response("", TokenUsage.of(9, 2, 11))
        );
        DeepSeekProvider provider = new DeepSeekProvider("https://api.deepseek.com", "test-key", 4096, chatModel);

        List<ProviderStreamChunk> chunks = provider.streamWithUsage(new ProviderRequest(
                new ModelSpec("deepseek", "deepseek-v4-flash", Set.of(ModelCapability.TEXT), true),
                "你是助手",
                "你好",
                List.of(),
                List.of()
            ))
            .collectList()
            .block();

        assertThat(chunks).isNotNull();
        assertThat(chunks.stream().filter(ProviderStreamChunk::hasContent).map(ProviderStreamChunk::content).toList())
            .containsExactly("最", "终");
        assertThat(chunks.stream().filter(ProviderStreamChunk::hasUsage).findFirst().orElseThrow()
            .usage().totalTokens()).isEqualTo(11);
    }

    @Test
    void streamReturnsFailureWhenToolCallingLoops() {
        AtomicInteger executedCalls = new AtomicInteger();
        CapturingChatModel chatModel = new CapturingChatModel(prompt -> response("", null));
        chatModel.streamHandler = prompt -> Flux.defer(() -> {
            DeepSeekChatOptions options = (DeepSeekChatOptions) prompt.getOptions();
            ToolCallback callback = options.getToolCallbacks().get(0);
            for (int i = 0; i < 9; i++) {
                callback.call("{\"query\":\"EchoMind\"}");
            }
            return Flux.just(response("不会走到这里", null));
        });
        DeepSeekProvider provider = new DeepSeekProvider("https://api.deepseek.com", "test-key", 4096, chatModel);

        List<ProviderStreamChunk> chunks = provider.streamWithUsage(new ProviderRequest(
                new ModelSpec("deepseek", "deepseek-v4-flash", Set.of(ModelCapability.TEXT, ModelCapability.FUNCTION), true),
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
            ))
            .collectList()
            .block();

        assertThat(chunks).isNotNull();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).failed()).isTrue();
        assertThat(chunks.get(0).failureReason()).contains("工具调用次数超过上限");
        assertThat(executedCalls.get()).isEqualTo(8);
    }
}
