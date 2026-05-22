package com.echomind.llm.provider;

import com.echomind.llm.router.ModelSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * DeepSeek Provider backed by Spring AI's chat-completions adapter.
 */
@Slf4j
public class DeepSeekProvider extends SpringAiProviderSupport {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    private final String baseUrl;
    private final String apiKey;
    private final int maxTokens;

    public DeepSeekProvider(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, 4096);
    }

    public DeepSeekProvider(String baseUrl, String apiKey, int maxTokens) {
        this(baseUrl, apiKey, maxTokens, chatModel(baseUrl, apiKey, maxTokens));
    }

    DeepSeekProvider(String baseUrl, String apiKey, int maxTokens, ChatModel chatModel) {
        super("deepseek", chatModel);
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.maxTokens = maxTokens > 0 ? maxTokens : 4096;
        if (isLegacyAnthropicBaseUrl(baseUrl)) {
            log.warn("DeepSeek /anthropic base-url is retired; using chat-completions base URL {}", this.baseUrl);
        }
    }

    @Override
    protected ChatOptions chatOptions(ModelSpec model, List<ToolCallback> toolCallbacks, String requiredToolName) {
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder()
            .model(model == null || model.modelName() == null || model.modelName().isBlank()
                ? DEFAULT_MODEL
                : model.modelName())
            .maxTokens(maxTokens);
        if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
            builder.toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(true);
            if (requiredToolName != null && !requiredToolName.isBlank()) {
                builder.toolChoice(DeepSeekApi.ChatCompletionRequest.ToolChoiceBuilder.FUNCTION(requiredToolName));
            }
        }
        return builder.build();
    }

    @Override
    protected boolean configured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    protected String notConfiguredMessage() {
        return "DeepSeek API key is not configured";
    }

    @Override
    protected boolean supportsAttachments() {
        return false;
    }

    @Override
    protected String unsupportedMultimodalMessage() {
        return "当前 DeepSeek Provider 暂不支持多模态图片输入";
    }

    static String normalizeBaseUrl(String value) {
        String normalized = trimTrailingSlashes(value);
        if (normalized.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        if (isLegacyAnthropicBaseUrl(normalized)) {
            normalized = normalized.substring(0, normalized.length() - "/anthropic".length());
        }
        return normalized;
    }

    static boolean isLegacyAnthropicBaseUrl(String value) {
        String normalized = trimTrailingSlashes(value);
        return normalized.endsWith("/anthropic");
    }

    private static ChatModel chatModel(String baseUrl, String apiKey, int maxTokens) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        Endpoint endpoint = chatCompletionEndpoint(normalizedBaseUrl, DEFAULT_BASE_URL, "/chat/completions");
        DeepSeekApi api = DeepSeekApi.builder()
            .baseUrl(endpoint.baseUrl())
            .completionsPath(endpoint.completionsPath())
            .apiKey(safeApiKey(apiKey))
            .build();
        return DeepSeekChatModel.builder()
            .deepSeekApi(api)
            .defaultOptions(DeepSeekChatOptions.builder()
                .model(DEFAULT_MODEL)
                .maxTokens(maxTokens > 0 ? maxTokens : 4096)
                .build())
            .toolCallingManager(defaultToolCallingManager())
            .retryTemplate(defaultRetryTemplate())
            .observationRegistry(observationRegistry())
            .build();
    }
}
