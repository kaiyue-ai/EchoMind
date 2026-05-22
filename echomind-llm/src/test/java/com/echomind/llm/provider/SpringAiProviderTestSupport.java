package com.echomind.llm.provider;

import com.echomind.common.model.TokenUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

final class SpringAiProviderTestSupport {

    private SpringAiProviderTestSupport() {
    }

    static ChatResponse response(String content, TokenUsage usage) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (usage != null) {
            metadata.usage(new DefaultUsage(
                (int) usage.promptTokens(),
                (int) usage.completionTokens(),
                (int) usage.totalTokens()
            ));
        }
        return new ChatResponse(
            List.of(new Generation(new AssistantMessage(content == null ? "" : content))),
            metadata.build()
        );
    }

    static final class CapturingChatModel implements ChatModel {
        private final Function<Prompt, ChatResponse> callHandler;
        Function<Prompt, Flux<ChatResponse>> streamHandler;
        Prompt lastPrompt;

        CapturingChatModel(Function<Prompt, ChatResponse> callHandler) {
            this.callHandler = callHandler;
            this.streamHandler = prompt -> Flux.just(callHandler.apply(prompt));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            return callHandler.apply(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            this.lastPrompt = prompt;
            return streamHandler.apply(prompt);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }
}
