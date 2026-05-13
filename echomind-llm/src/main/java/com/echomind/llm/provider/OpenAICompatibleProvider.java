package com.echomind.llm.provider;

import com.echomind.common.model.MessageAttachment;
import com.echomind.llm.router.ModelSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 通用 OpenAI 兼容协议 Provider。
 *
 * <p>覆盖所有使用 OpenAI API 格式的模型服务：OpenAI、通义千问、Ollama、vLLM 等。
 * providerId 从构造参数注入，不再硬编码，通过 YAML 配置即可接入新供应商。</p>
 */
@Slf4j
public class OpenAICompatibleProvider implements ModelProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String providerId;
    private final OpenAiChatModel chatModel;
    private final String baseUrl;
    private final String apiKey;
    private final OkHttpClient httpClient = new OkHttpClient();

    public OpenAICompatibleProvider(String providerId, OpenAiChatModel chatModel) {
        this(providerId, chatModel, null, null);
    }

    public OpenAICompatibleProvider(String providerId, OpenAiChatModel chatModel, String baseUrl, String apiKey) {
        this.providerId = providerId;
        this.chatModel = chatModel;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public boolean supports(ModelSpec model) {
        return providerId.equals(model.providerId());
    }

    @Override
    public String chat(ModelSpec model, String systemPrompt, String userMessage) {
        if (baseUrl != null && apiKey != null) {
            try {
                return callChatCompletions(model, systemPrompt, userMessage, List.of(), List.of());
            } catch (Exception e) {
                log.error("{} API call failed", providerId, e);
                return "[Error] " + e.getMessage();
            }
        }
        try {
            var spec = ChatClient.create(chatModel).prompt().user(userMessage);
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                spec = spec.system(systemPrompt);
            }
            return spec.call().content();
        } catch (Exception e) {
            log.error("{} API call failed", providerId, e);
            return "[Error] " + e.getMessage();
        }
    }

    @Override
    public String chat(ModelSpec model, String systemPrompt, String userMessage,
                       List<MessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return chat(model, systemPrompt, userMessage);
        }
        if (baseUrl == null || apiKey == null) {
            return "[Error] 当前 Provider 没有配置 OpenAI 兼容多模态调用参数";
        }
        try {
            return callChatCompletions(model, systemPrompt, userMessage, attachments, List.of());
        } catch (Exception e) {
            log.error("{} multimodal API call failed", providerId, e);
            return "[Error] " + e.getMessage();
        }
    }

    @Override
    public Flux<String> stream(ModelSpec model, String systemPrompt, String userMessage,
                               List<MessageAttachment> attachments) {
        return Flux.just(chat(model, systemPrompt, userMessage, attachments));
    }

    @Override
    public Flux<String> stream(ModelSpec model, String systemPrompt, String userMessage) {
        if (baseUrl != null && apiKey != null) {
            return Flux.just(chat(model, systemPrompt, userMessage));
        }
        var spec = ChatClient.create(chatModel).prompt().user(userMessage);
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
    public String chatWithTools(ModelSpec model, String systemPrompt, String userMessage, List<LlmTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return chat(model, systemPrompt, userMessage);
        }
        if (baseUrl == null || apiKey == null) {
            return chat(model, systemPrompt, userMessage);
        }
        try {
            return callChatCompletions(model, systemPrompt, userMessage, List.of(), tools);
        } catch (Exception e) {
            log.error("{} tool API call failed", providerId, e);
            return "[Error] " + e.getMessage();
        }
    }

    @Override
    public Flux<String> streamWithTools(ModelSpec model, String systemPrompt, String userMessage,
                                        List<LlmTool> tools) {
        return Flux.just(chatWithTools(model, systemPrompt, userMessage, tools));
    }

    @Override
    public org.springframework.ai.chat.model.ChatModel getChatModel() { return chatModel; }

    /**
     * 图片消息直接使用 OpenAI Chat Completions 兼容协议发送。
     *
     * <p>当前 Spring AI 版本在项目里的调用封装只走纯文本 prompt，所以这里对带图片的
     * 请求走一条明确的 JSON 调用路径。百炼的 qwen-vl 系列兼容这种 content 数组格式。</p>
     */
    private String callChatCompletions(ModelSpec model, String systemPrompt, String userMessage,
                                       List<MessageAttachment> attachments,
                                       List<LlmTool> tools) throws IOException {
        List<Map<String, Object>> messages = new ArrayList<>();
        String toolPrompt = ToolCallSupport.toolUseInstruction(userMessage, tools);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt + "\n\n" + toolPrompt));
        } else if (tools != null && !tools.isEmpty()) {
            messages.add(Map.of("role", "system", "content", toolPrompt));
        }

        messages.add(userMessage(userMessage, attachments));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.modelName());
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(this::toOpenAiTool).toList());
            body.put("tool_choice", "auto");
        }

        Request request = new Request.Builder()
            .url(chatCompletionsUrl())
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(MAPPER.writeValueAsBytes(body), JSON))
            .build();

        try (var response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + responseBody);
            }
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode message = root.path("choices").path(0).path("message");
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray() && !toolCalls.isEmpty() && tools != null && !tools.isEmpty()) {
                for (JsonNode toolCall : toolCalls) {
                    String toolName = toolCall.path("function").path("name").asText("");
                    String arguments = toolCall.path("function").path("arguments").asText("{}");
                    LlmTool tool = tools.stream()
                        .filter(t -> Objects.equals(t.name(), toolName))
                        .findFirst()
                        .orElse(null);
                    if (tool == null) {
                        continue;
                    }
                    messages.add(toolAssistantMessage(toolCall));
                    messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", toolCall.path("id").asText(),
                        "content", tool.call(arguments)
                    ));
                }
                return continueAfterToolCalls(model, messages);
            }
            String content = contentText(message.path("content"));
            LlmTool degradedTool = ToolCallSupport.detectDegradedToolSelection(content, tools);
            if (degradedTool != null) {
                appendDegradedToolExecution(messages, content, degradedTool, userMessage);
                return continueAfterToolCalls(model, messages);
            }
            return content;
        }
    }

    private void appendDegradedToolExecution(List<Map<String, Object>> messages, String assistantText,
                                             LlmTool tool, String userMessage) {
        String argumentsJson = ToolCallSupport.defaultArgumentsJson(tool, userMessage);
        messages.add(Map.of("role", "assistant", "content", assistantText == null ? tool.name() : assistantText));
        messages.add(Map.of(
            "role", "tool",
            "tool_call_id", "degraded-" + tool.name(),
            "content", """
                Requested tool: %s
                Arguments: %s
                Result:
                %s

                %s
                """.formatted(
                tool.name(),
                argumentsJson,
                tool.call(argumentsJson),
                ToolCallSupport.finalAnswerInstruction()
            )
        ));
    }

    private String continueAfterToolCalls(ModelSpec model, List<Map<String, Object>> messages) throws IOException {
        Map<String, Object> body = Map.of(
            "model", model.modelName(),
            "messages", messages
        );
        Request request = new Request.Builder()
            .url(chatCompletionsUrl())
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(MAPPER.writeValueAsBytes(body), JSON))
            .build();
        try (var response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + responseBody);
            }
            JsonNode root = MAPPER.readTree(responseBody);
            return contentText(root.path("choices").path(0).path("message").path("content"));
        }
    }

    private Map<String, Object> userMessage(String userMessage, List<MessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Map.of("role", "user", "content", userMessage);
        }
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", userMessage));
        for (MessageAttachment attachment : attachments) {
            if (attachment == null || !attachment.isImage()) {
                continue;
            }
            String imageUrl = attachment.url();
            if (imageUrl == null || imageUrl.isBlank()) {
                throw new IllegalArgumentException("图片附件缺少可访问 URL: " + attachment.uri());
            }
            content.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", imageUrl)
            ));
        }
        return Map.of("role", "user", "content", content);
    }

    private Map<String, Object> toOpenAiTool(LlmTool tool) {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", tool.name(),
                "description", tool.description() == null ? "" : tool.description(),
                "parameters", tool.parameters() == null ? Map.of("type", "object", "properties", Map.of()) : tool.parameters()
            )
        );
    }

    private Map<String, Object> toolAssistantMessage(JsonNode toolCall) {
        return Map.of(
            "role", "assistant",
            "content", "",
            "tool_calls", List.of(MAPPER.convertValue(toolCall, Map.class))
        );
    }

    private String contentText(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : contentNode) {
                JsonNode text = item.path("text");
                if (text.isTextual()) {
                    sb.append(text.asText());
                }
            }
            return sb.toString();
        }
        return contentNode.asText("");
    }

    private String chatCompletionsUrl() {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        return normalized + "/chat/completions";
    }
}
