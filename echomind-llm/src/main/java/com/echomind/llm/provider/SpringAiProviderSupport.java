package com.echomind.llm.provider;

import com.echomind.common.model.MessageAttachment;
import com.echomind.common.model.TokenUsage;
import com.echomind.common.util.JsonSchemaValidator;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.dto.ProviderResponse;
import com.echomind.llm.provider.tool.LlmTool;
import com.echomind.llm.router.ModelSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** EchoMind ModelProvider 与 Spring AI ChatModel 之间的通用适配层。 */
@Slf4j
abstract class SpringAiProviderSupport implements ModelProvider {

    /** 单次模型请求最多允许执行的工具次数，防止模型反复调用同一个工具导致请求永不结束。 */
    private static final int MAX_TOOL_CALLS_PER_REQUEST = 8;

    // 这是json序列化工具,用于将java对象转为json字符串,将json字符串解析为javaMap对象
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 这是jackson的类型引用,用于反序列化时指定泛型的类型
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    // 供应商的id
    private final String providerId;
    // 大模型对话对象
    private final ChatModel chatModel;

    SpringAiProviderSupport(String providerId, ChatModel chatModel) {
        this.providerId = providerId;
        this.chatModel = chatModel;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    // 判断是否支持某个模型
    public boolean supports(ModelSpec model) {
        return model != null && providerId.equals(model.providerId());
    }

    @Override
    // 大模型对话
    public ProviderResponse chatWithUsage(ProviderRequest request) {
        // 检验一下
        String failureReason = preflightFailure(request);
        if (failureReason != null) {
            return ProviderResponse.failure(failureReason);
        }
        try {
            ChatResponse response = chatModel.call(prompt(request));
            return new ProviderResponse(cleanToolError(content(response)), usage(response));
        } catch (Exception e) {
            log.warn("{} Spring AI chat call failed: {}", providerId, e.getMessage());
            log.debug("{} Spring AI chat call failure detail", providerId, e);
            return ProviderResponse.failure(e.getMessage());
        }
    }

    @Override
    // 流式对话
    public Flux<ProviderStreamChunk> streamWithUsage(ProviderRequest request) {
        // 依旧检验
        String failureReason = preflightFailure(request);
        if (failureReason != null) {
            return Flux.just(ProviderStreamChunk.failure(failureReason));
        }
        try {
            Prompt prompt = prompt(request);
            return chatModel.stream(prompt)
                .flatMapIterable(this::chunks)
                .onErrorResume(error -> {
                    log.warn("{} Spring AI streaming call failed: {}", providerId, error.getMessage());
                    log.debug("{} Spring AI streaming call failure detail", providerId, error);
                    return Flux.just(ProviderStreamChunk.failure(error.getMessage()));
                });
        } catch (Exception e) {
            log.warn("{} Spring AI streaming setup failed: {}", providerId, e.getMessage());
            log.debug("{} Spring AI streaming setup failure detail", providerId, e);
            return Flux.just(ProviderStreamChunk.failure(e.getMessage()));
        }
    }


    protected abstract ChatOptions chatOptions(ModelSpec model, List<ToolCallback> toolCallbacks,
                                               String requiredToolName);

    protected abstract boolean configured();

    protected abstract String notConfiguredMessage();

    protected boolean supportsAttachments() {
        return true;
    }

    protected String unsupportedMultimodalMessage() {
        return "当前模型 Provider 暂不支持多模态图片输入";
    }

    protected static ToolCallingManager defaultToolCallingManager() {
        return ToolCallingManager.builder().build();
    }

    protected static RetryTemplate defaultRetryTemplate() {
        return RetryTemplate.defaultInstance();
    }

    protected static ObservationRegistry observationRegistry() {
        return ObservationRegistry.NOOP;
    }

    protected static String safeApiKey(String apiKey) {
        return apiKey == null || apiKey.isBlank() ? "missing-api-key" : apiKey;
    }

    protected static Endpoint chatCompletionEndpoint(String rawBaseUrl, String defaultBaseUrl,
                                                     String defaultCompletionsPath) {
        String normalized = trimTrailingSlashes(rawBaseUrl == null || rawBaseUrl.isBlank()
            ? defaultBaseUrl
            : rawBaseUrl);
        if (normalized.endsWith("/chat/completions")) {
            return new Endpoint(
                normalized.substring(0, normalized.length() - "/chat/completions".length()),
                "/chat/completions"
            );
        }
        if (normalized.endsWith("/v1")) {
            return new Endpoint(normalized, "/chat/completions");
        }
        return new Endpoint(normalized, defaultCompletionsPath);
    }

    protected static String trimTrailingSlashes(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    protected record Endpoint(String baseUrl, String completionsPath) {}

    // 首先检查是否配置,请求是否合规,是否有附件的请求
    private String preflightFailure(ProviderRequest request) {
        if (!configured()) {
            return notConfiguredMessage();
        }
        if (request == null) {
            return "Provider request is missing";
        }
        if (request.hasAttachments() && !supportsAttachments()) {
            return unsupportedMultimodalMessage();
        }
        return null;
    }

    // 构建请求对象
    private Prompt prompt(ProviderRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }
        messages.add(userMessage(request.userMessage(), request.attachments()));
        return new Prompt(messages, chatOptions(
            request.model(),
            toolCallbacks(request.tools()),
            request.requiredToolName()
        ));
    }

    private UserMessage userMessage(String text, List<MessageAttachment> attachments) {
        List<Media> media = attachments == null
            ? List.of()
            : attachments.stream()
                .filter(attachment -> attachment != null && attachment.isImage())
                .map(this::media)
                .toList();
        if (media.isEmpty()) {
            return new UserMessage(text == null ? "" : text);
        }
        return UserMessage.builder()
            .text(text == null ? "" : text)
            .media(media)
            .build();
    }

    private Media media(MessageAttachment attachment) {
        String url = attachment.url();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("图片附件缺少可访问 URL: " + attachment.uri());
        }
        MimeType mimeType = attachment.mimeType() == null || attachment.mimeType().isBlank()
            ? MimeTypeUtils.IMAGE_PNG
            : MimeTypeUtils.parseMimeType(attachment.mimeType());
        return new Media(mimeType, URI.create(url));
    }

    private List<ToolCallback> toolCallbacks(List<LlmTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        ToolCallBudget budget = new ToolCallBudget(MAX_TOOL_CALLS_PER_REQUEST);
        return tools.stream().map(tool -> new EchoMindToolCallback(tool, budget)).map(ToolCallback.class::cast).toList();
    }

    private List<ProviderStreamChunk> chunks(ChatResponse response) {
        List<ProviderStreamChunk> chunks = new ArrayList<>();
        String content = cleanToolError(content(response));
        if (!content.isEmpty()) {
            chunks.add(ProviderStreamChunk.text(content));
        }
        TokenUsage usage = usage(response);
        if (usage != null) {
            chunks.add(ProviderStreamChunk.usage(usage));
        }
        return chunks;
    }

    private String content(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private TokenUsage usage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return null;
        }
        return TokenUsage.of(
            number(usage.getPromptTokens()),
            number(usage.getCompletionTokens()),
            number(usage.getTotalTokens())
        );
    }

    private long number(Integer value) {
        return value == null ? 0 : value;
    }

    private static String cleanToolError(String content) {
        if (content == null) {
            return "";
        }
        return content.trim().replaceFirst("^Error:\\s*", "");
    }

    private static final class EchoMindToolCallback implements ToolCallback {

        private final LlmTool tool;
        private final ToolCallBudget budget;
        private final ToolDefinition definition;
        private final ToolMetadata metadata;

        private EchoMindToolCallback(LlmTool tool, ToolCallBudget budget) {
            this.tool = tool;
            this.budget = budget;
            this.definition = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description() == null ? "" : tool.description())
                .inputSchema(inputSchema(tool))
                .build();
            this.metadata = ToolMetadata.builder()
                .returnDirect(tool.directResult())
                .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return metadata;
        }

        @Override
        public String call(String toolInput) {
            budget.acquire(tool.name());
            String argumentsJson = toolInput == null || toolInput.isBlank() ? "{}" : toolInput;
            String validationError = validateToolArguments(tool, argumentsJson);
            if (validationError != null) {
                return "Error: " + validationError;
            }
            try {
                String result = tool.call(argumentsJson);
                return result == null ? "" : result;
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        private static String inputSchema(LlmTool tool) {
            Map<String, Object> schema = new LinkedHashMap<>(
                tool.parameters() == null ? Map.of() : tool.parameters());
            schema.putIfAbsent("type", "object");
            schema.putIfAbsent("properties", Map.of());
            try {
                return MAPPER.writeValueAsString(schema);
            } catch (Exception e) {
                return "{\"type\":\"object\",\"properties\":{}}";
            }
        }

        private static String validateToolArguments(LlmTool tool, String argumentsJson) {
            Map<String, Object> arguments;
            try {
                arguments = MAPPER.readValue(argumentsJson, MAP_TYPE);
            } catch (Exception e) {
                return "工具参数不是合法 JSON 对象：" + e.getMessage();
            }
            List<String> errors = JsonSchemaValidator.validate(tool.parameters(), arguments);
            return errors.isEmpty() ? null : String.join("; ", errors);
        }
    }

    private static final class ToolCallBudget {

        private final int maxCalls;
        private final AtomicInteger used = new AtomicInteger();

        private ToolCallBudget(int maxCalls) {
            this.maxCalls = maxCalls;
        }

        private void acquire(String toolName) {
            int current = used.incrementAndGet();
            if (current <= maxCalls) {
                return;
            }
            String message = "工具调用次数超过上限(" + maxCalls + ")，已中止本轮模型调用，防止工具循环。最后一次工具: "
                + (toolName == null ? "" : toolName);
            log.warn(message);
            throw new IllegalStateException(message);
        }
    }
}
