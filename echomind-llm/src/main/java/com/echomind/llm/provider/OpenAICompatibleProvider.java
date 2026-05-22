package com.echomind.llm.provider;

import com.echomind.llm.router.ModelSpec;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * OpenAI-compatible Provider backed by Spring AI.
 */
// 这里不提供对话服务,只返回spring ai的对话对象
public class OpenAICompatibleProvider extends SpringAiProviderSupport {

    private final String baseUrl;
    private final String apiKey;

    public OpenAICompatibleProvider(String providerId, String baseUrl, String apiKey) {
        this(providerId, baseUrl, apiKey, chatModel(baseUrl, apiKey));
    }

    OpenAICompatibleProvider(String providerId, String baseUrl, String apiKey, ChatModel chatModel) {
        super(providerId, chatModel);
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    // 获取调用api的请求体
    protected ChatOptions chatOptions(ModelSpec model, List<ToolCallback> toolCallbacks, String requiredToolName) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
            .model(model.modelName())
            .streamUsage(true);
        if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
            builder.toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(true);
            if (requiredToolName != null && !requiredToolName.isBlank()) {
                builder.toolChoice(OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.function(requiredToolName));
            }
        }
        return builder.build();
    }

    @Override
    // 判断是否配置
    protected boolean configured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    // 未配置
    protected String notConfiguredMessage() {
        return "OpenAI-compatible provider is not configured";
    }

    // 返回对话对象ChatModel
    private static ChatModel chatModel(String baseUrl, String apiKey) {
        Endpoint endpoint = chatCompletionEndpoint(baseUrl, "https://api.openai.com", "/v1/chat/completions");
        OpenAiApi api = OpenAiApi.builder()
            .baseUrl(endpoint.baseUrl())
            .completionsPath(endpoint.completionsPath())
            .apiKey(safeApiKey(apiKey))
            .build();
        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().build())
            .toolCallingManager(defaultToolCallingManager())
            .retryTemplate(defaultRetryTemplate())
            .observationRegistry(observationRegistry())
            .build();
    }
}
