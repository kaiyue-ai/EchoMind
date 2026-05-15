package com.echomind.llm.provider;

import com.echomind.common.model.MessageAttachment;
import com.echomind.llm.router.ModelSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
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
    private final String baseUrl;
    private final String apiKey;
    private final OkHttpClient httpClient;

    public OpenAICompatibleProvider(String providerId, String baseUrl, String apiKey) {
        this(providerId, baseUrl, apiKey, new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build());
    }

    OpenAICompatibleProvider(String providerId, String baseUrl, String apiKey, OkHttpClient httpClient) {
        this.providerId = providerId;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
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
    public String chat(ProviderRequest request) {
        try {
            requireConfigured();
            return callChatCompletions(
                request.model(),
                request.systemPrompt(),
                request.userMessage(),
                request.attachments(),
                request.tools()
            );
        } catch (Exception e) {
            log.error("{} API call failed", providerId, e);
            return "[Error] " + e.getMessage();
        }
    }

    @Override
    public Flux<String> stream(ProviderRequest request) {
        try {
            requireConfigured();
            return streamChatCompletions(
                request.model(),
                request.systemPrompt(),
                request.userMessage(),
                request.attachments(),
                request.tools()
            );
        } catch (Exception e) {
            log.error("{} streaming API call failed", providerId, e);
            return Flux.just("[Error] " + e.getMessage());
        }
    }

    /**
     * 图片消息直接使用 OpenAI Chat Completions 兼容协议发送。
     *
     * <p>多模态和工具调用都走 provider 自己的协议实现，不依赖外部 SDK 的函数调用封装。
     * 百炼的 qwen-vl 系列兼容这种 content 数组格式。</p>
     */
    private String callChatCompletions(ModelSpec model, String systemPrompt, String userMessage,
                                       List<MessageAttachment> attachments,
                                       List<LlmTool> tools) throws IOException {
        List<Map<String, Object>> messages = buildMessages(systemPrompt, userMessage, attachments, tools);
        Map<String, Object> body = buildChatCompletionsBody(model, messages, tools, false);
        JsonNode root = sendChatCompletions(body);
        JsonNode message = root.path("choices").path(0).path("message");
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty() && tools != null && !tools.isEmpty()) {
            for (JsonNode toolCall : toolCalls) {
                String toolName = toolCall.path("function").path("name").asText("");
                String arguments = toolCall.path("function").path("arguments").asText("{}");
                LlmTool tool = findTool(toolName, tools);
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

    private List<Map<String, Object>> buildMessages(String systemPrompt, String userMessage,
                                                    List<MessageAttachment> attachments,
                                                    List<LlmTool> tools) {
        List<Map<String, Object>> messages = new ArrayList<>();
        String toolPrompt = ToolCallSupport.toolUseInstruction(userMessage, tools);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt + "\n\n" + toolPrompt));
        } else if (tools != null && !tools.isEmpty()) {
            messages.add(Map.of("role", "system", "content", toolPrompt));
        }
        messages.add(userMessage(userMessage, attachments));
        return messages;
    }

    private Map<String, Object> buildChatCompletionsBody(ModelSpec model, List<Map<String, Object>> messages,
                                                         List<LlmTool> tools, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.modelName());
        body.put("messages", messages);
        if (stream) {
            body.put("stream", true);
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(this::toOpenAiTool).toList());
            body.put("tool_choice", "auto");
        }
        return body;
    }

    private JsonNode sendChatCompletions(Map<String, Object> body) throws IOException {
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
            return MAPPER.readTree(responseBody);
        }
    }

    private Flux<String> streamChatCompletions(ModelSpec model, String systemPrompt, String userMessage,
                                               List<MessageAttachment> attachments,
                                               List<LlmTool> tools) {
        return Flux.<String>create(sink -> {
            try {
                List<Map<String, Object>> messages = buildMessages(systemPrompt, userMessage, attachments, tools);
                StreamingOpenAiResult result = streamChatCompletionsBody(
                    buildChatCompletionsBody(model, messages, tools, true), sink);

                if (!result.toolCalls().isEmpty() && tools != null && !tools.isEmpty() && !sink.isCancelled()) {
                    appendOpenAiToolResults(messages, result.toolCalls(), tools);
                    String finalAnswer = continueAfterToolCalls(model, messages);
                    if (!finalAnswer.isEmpty() && !sink.isCancelled()) {
                        sink.next(finalAnswer);
                    }
                }

                if (!sink.isCancelled()) {
                    sink.complete();
                }
            } catch (Exception e) {
                if (!sink.isCancelled()) {
                    sink.error(e);
                }
            }
        }).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                log.error("{} streaming API call failed", providerId, e);
                return Flux.just("[Error] " + e.getMessage());
            });
    }

    private StreamingOpenAiResult streamChatCompletionsBody(Map<String, Object> body,
                                                            FluxSink<String> sink) throws IOException {
        Request request = new Request.Builder()
            .url(chatCompletionsUrl())
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(RequestBody.create(MAPPER.writeValueAsBytes(body), JSON))
            .build();
        Call call = httpClient.newCall(request);

        StringBuilder content = new StringBuilder();
        Map<Integer, OpenAiToolCallBuilder> toolCallBuilders = new TreeMap<>();
        try (var response = call.execute()) {
            var responseBody = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBody == null ? "" : responseBody.string();
                throw new IOException("HTTP " + response.code() + ": " + errorBody);
            }
            if (responseBody == null) {
                throw new IOException("Empty streaming response body");
            }
            var source = responseBody.source();
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (sink.isCancelled()) {
                    break;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                handleOpenAiStreamData(data, sink, content, toolCallBuilders);
            }
        } catch (IOException e) {
            if (sink.isCancelled()) {
                return new StreamingOpenAiResult(content.toString(), toolCalls(toolCallBuilders));
            }
            throw e;
        }
        return new StreamingOpenAiResult(content.toString(), toolCalls(toolCallBuilders));
    }

    private void handleOpenAiStreamData(String data, FluxSink<String> sink, StringBuilder content,
                                        Map<Integer, OpenAiToolCallBuilder> toolCallBuilders) throws IOException {
        JsonNode root = MAPPER.readTree(data);
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IOException(error.path("message").asText(error.toString()));
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray()) {
            return;
        }
        for (JsonNode choice : choices) {
            JsonNode delta = choice.path("delta");
            String token = contentText(delta.path("content"));
            if (!token.isEmpty()) {
                content.append(token);
                sink.next(token);
            }
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode toolCall : toolCalls) {
                    appendToolCallDelta(toolCallBuilders, toolCall);
                }
            }
        }
    }

    private void appendToolCallDelta(Map<Integer, OpenAiToolCallBuilder> builders, JsonNode toolCall) {
        int index = toolCall.path("index").asInt(builders.size());
        OpenAiToolCallBuilder builder = builders.computeIfAbsent(index, ignored -> new OpenAiToolCallBuilder());
        String id = toolCall.path("id").asText("");
        if (!id.isBlank()) {
            builder.id = id;
        }
        JsonNode function = toolCall.path("function");
        String name = function.path("name").asText("");
        if (!name.isBlank()) {
            builder.name = name;
        }
        String arguments = function.path("arguments").asText("");
        if (!arguments.isEmpty()) {
            builder.arguments.append(arguments);
        }
    }

    private List<OpenAiToolCall> toolCalls(Map<Integer, OpenAiToolCallBuilder> builders) {
        List<OpenAiToolCall> calls = new ArrayList<>();
        for (var entry : builders.entrySet()) {
            OpenAiToolCallBuilder builder = entry.getValue();
            if (builder.name == null || builder.name.isBlank()) {
                continue;
            }
            String id = builder.id == null || builder.id.isBlank() ? "call_" + entry.getKey() : builder.id;
            calls.add(new OpenAiToolCall(id, builder.name, builder.arguments.toString()));
        }
        return calls;
    }

    private void appendOpenAiToolResults(List<Map<String, Object>> messages, List<OpenAiToolCall> toolCalls,
                                         List<LlmTool> tools) {
        messages.add(Map.of(
            "role", "assistant",
            "content", "",
            "tool_calls", toolCalls.stream().map(this::toToolCallMessage).toList()
        ));
        for (OpenAiToolCall call : toolCalls) {
            LlmTool tool = findTool(call.name(), tools);
            if (tool == null) {
                continue;
            }
            messages.add(Map.of(
                "role", "tool",
                "tool_call_id", call.id(),
                "content", tool.call(call.arguments().isBlank() ? "{}" : call.arguments())
            ));
        }
    }

    private Map<String, Object> toToolCallMessage(OpenAiToolCall call) {
        return Map.of(
            "id", call.id(),
            "type", "function",
            "function", Map.of(
                "name", call.name(),
                "arguments", call.arguments().isBlank() ? "{}" : call.arguments()
            )
        );
    }

    private LlmTool findTool(String toolName, List<LlmTool> tools) {
        if (tools == null || toolName == null) {
            return null;
        }
        return tools.stream()
            .filter(t -> Objects.equals(t.name(), toolName))
            .findFirst()
            .orElse(null);
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

    private void requireConfigured() throws IOException {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IOException("OpenAI-compatible provider is not configured");
        }
    }

    private record StreamingOpenAiResult(String content, List<OpenAiToolCall> toolCalls) {}

    private record OpenAiToolCall(String id, String name, String arguments) {}

    private static class OpenAiToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
