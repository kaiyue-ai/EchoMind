package com.echomind.llm.provider;

import com.echomind.llm.router.ModelSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/**
 * Anthropic（Claude）模型提供商。
 *
 * <p>同步调用和流式调用都委托给 Spring AI 的 {@link ChatClient}。
 * 工具选择主要由 Agent 管线处理，因此这里保持模型调用本身足够薄。</p>
 *
 * @see ModelProvider
 * @see OpenAICompatibleProvider
 */
@Slf4j
@RequiredArgsConstructor
public class AnthropicProvider implements ModelProvider {

    private final AnthropicChatModel chatModel;

    @Override
    public String providerId() {
        return "anthropic";
    }

    @Override
    public boolean supports(ModelSpec model) {
        return "anthropic".equals(model.providerId());
    }

    @Override
    public String chat(ModelSpec model, String systemPrompt, String userMessage) {
        try {
            ChatClient client = ChatClient.create(chatModel);
            var spec = client.prompt().user(userMessage);
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                spec = spec.system(systemPrompt);
            }
            return spec.call().content();
        } catch (Exception e) {
            log.error("Anthropic API call failed", e);
            return "[Error] " + e.getMessage();
        }
    }

    @Override
    public Flux<String> stream(ModelSpec model, String systemPrompt, String userMessage) {
        ChatClient client = ChatClient.create(chatModel);
        var spec = client.prompt().user(userMessage);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            spec = spec.system(systemPrompt);
        }
        return spec.stream().content();
    }

    @Override
    public String chatWithTools(ModelSpec model, String systemPrompt, String userMessage, String toolsJson) {
        return chat(model, systemPrompt, userMessage);
    }

    @Override
    public org.springframework.ai.chat.model.ChatModel getChatModel() { return chatModel; }
}
