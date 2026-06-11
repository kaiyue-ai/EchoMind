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
import java.util.Map;

/**
 * DeepSeek Provider backed by Spring AI's chat-completions adapter.
 */
@Slf4j
public class DeepSeekProvider extends SpringAiProviderSupport {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final String V4_FLASH = "deepseek-v4-flash";
    private static final String V4_FLASH_NON_THINKING_ALIAS = "deepseek-chat";

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
        boolean hasTools = toolCallbacks != null && !toolCallbacks.isEmpty();
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder()
            .model(modelName(model, hasTools))
            .maxTokens(maxTokens);
        if (hasTools) {
            builder.toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(true);
        }
        DeepSeekChatOptions options = builder.build();
        if (hasTools && requiredToolName != null && !requiredToolName.isBlank()) {
            options.setToolChoice(functionToolChoice(requiredToolName));
        }
        return options;
    }

    private Map<String, Object> functionToolChoice(String toolName) {
        return Map.of(
            "type", "function",
            "function", Map.of("name", toolName)
        );
    }

    private String modelName(ModelSpec model, boolean hasTools) {
        String requested = model == null || model.modelName() == null || model.modelName().isBlank()
            ? DEFAULT_MODEL
            : model.modelName();
        if (hasTools && V4_FLASH.equals(requested)) {
            return V4_FLASH_NON_THINKING_ALIAS;
        }
        return requested;
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
