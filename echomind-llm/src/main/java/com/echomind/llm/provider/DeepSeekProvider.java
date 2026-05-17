package com.echomind.llm.provider;

import com.echomind.common.model.MessageAttachment;
import com.echomind.llm.router.ModelSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek Anthropic-compatible Provider.
 *
 * <p>DeepSeek 的 Anthropic 兼容接口可能返回 {@code thinking} content block。
 * 这里直接走自研 JSON 协议实现，只抽取 {@code text} block，忽略推理块。</p>
 */
@Slf4j
public class DeepSeekProvider implements ModelProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_TOOL_ROUNDS = 3;
    private static final Pattern DSML_INVOKE_PATTERN = Pattern.compile(
        "<(?:｜｜DSML｜｜|\\|\\|DSML\\|\\|)invoke\\s+name=\"([^\"]+)\"[^>]*>(.*?)</(?:｜｜DSML｜｜|\\|\\|DSML\\|\\|)invoke>",
        Pattern.DOTALL
    );
    private static final Pattern DSML_PARAMETER_PATTERN = Pattern.compile(
        "<(?:｜｜DSML｜｜|\\|\\|DSML\\|\\|)parameter\\s+name=\"([^\"]+)\"[^>]*>(.*?)</(?:｜｜DSML｜｜|\\|\\|DSML\\|\\|)parameter>",
        Pattern.DOTALL
    );

    private final String baseUrl;
    private final String apiKey;
    private final int maxTokens;
    private final OkHttpClient httpClient;

    public DeepSeekProvider(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, 4096);
    }

    public DeepSeekProvider(String baseUrl, String apiKey, int maxTokens) {
        this(baseUrl, apiKey, maxTokens, new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build());
    }

    DeepSeekProvider(String baseUrl, String apiKey, OkHttpClient httpClient) {
        this(baseUrl, apiKey, 4096, httpClient);
    }

    DeepSeekProvider(String baseUrl, String apiKey, int maxTokens, OkHttpClient httpClient) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.maxTokens = maxTokens > 0 ? maxTokens : 4096;
        this.httpClient = httpClient;
    }

    @Override
    public String providerId() {
        return "deepseek";
    }

    @Override
    public boolean supports(ModelSpec model) {
        return "deepseek".equals(model.providerId());
    }

    @Override
    public String chat(ProviderRequest request) {
        if (request.hasAttachments()) {
            return unsupportedMultimodalMessage();
        }
        try {
            return callMessages(request.model(), request.systemPrompt(), request.userMessage(),
                request.tools(), request.requiredToolName(), request.toolInputMessage());
        } catch (Exception e) {
            log.error("DeepSeek API call failed", e);
            return "[Error] " + e.getMessage();
        }
    }

    @Override
    public Flux<String> stream(ProviderRequest request) {
        if (request.hasAttachments()) {
            return Flux.just(unsupportedMultimodalMessage());
        }
        return streamMessages(request.model(), request.systemPrompt(), request.userMessage(), request.tools(),
            request.requiredToolName(), request.toolInputMessage());
    }

    private Flux<String> streamMessages(ModelSpec model, String systemPrompt, String userMessage,
                                        List<LlmTool> tools, String requiredToolName, String toolInputMessage) {
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.just("[Error] DeepSeek API key is not configured");
        }
        return Flux.<String>create(sink -> {
            try {
                List<LlmTool> activeTools = tools == null ? List.of() : tools;
                StringBuilder executedToolResults = new StringBuilder();
                List<Map<String, Object>> messages = buildMessages(systemPrompt, userMessage, activeTools);
                if (requiredToolName != null && !requiredToolName.isBlank()) {
                    executeRequiredTool(messages, requiredToolName, activeTools,
                        toolInputMessage == null ? userMessage : toolInputMessage, executedToolResults);
                    messages.add(Map.of("role", "user", "content", forcedFinalAnswerPrompt(executedToolResults)));
                    StreamingDeepSeekResult result = streamMessagesBody(
                        buildRequestBody(model, messages, List.of()), sink, false);
                    emitHeldText(result, sink);
                    if (!sink.isCancelled()) {
                        sink.complete();
                    }
                    return;
                }
                boolean finished = false;

                for (int round = 0; round < MAX_TOOL_ROUNDS && !sink.isCancelled(); round++) {
                    StreamingDeepSeekResult result = streamMessagesBody(
                        buildRequestBody(model, messages, activeTools, requiredToolName), sink, !activeTools.isEmpty());
                    if (activeTools.isEmpty()) {
                        emitHeldText(result, sink);
                        finished = true;
                        break;
                    }
                    if (!result.toolUses().isEmpty()) {
                        appendStreamedNativeToolResults(messages, result.toolUses(), activeTools, executedToolResults);
                        continue;
                    }
                    List<DsmlToolCall> dsmlToolCalls = dsmlToolCalls(result.content());
                    if (!dsmlToolCalls.isEmpty()) {
                        appendDsmlToolResults(messages, result.content(), dsmlToolCalls, activeTools, executedToolResults);
                        continue;
                    }
                    LlmTool degradedTool = ToolCallSupport.detectDegradedToolSelection(result.content(), activeTools);
                    if (degradedTool != null) {
                        appendDegradedToolResult(messages, result.content(), degradedTool, userMessage, executedToolResults);
                        continue;
                    }
                    emitHeldText(result, sink);
                    finished = true;
                    break;
                }

                if (!finished && !sink.isCancelled()) {
                    messages.add(Map.of("role", "user", "content", forcedFinalAnswerPrompt(executedToolResults)));
                    StreamingDeepSeekResult result = streamMessagesBody(
                        buildRequestBody(model, messages, List.of()), sink, false);
                    emitHeldText(result, sink);
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
                log.error("DeepSeek streaming API call failed", e);
                return Flux.just("[Error] " + e.getMessage());
            });
    }

    private String callMessages(ModelSpec model, String systemPrompt, String userMessage) throws IOException {
        return callMessages(model, systemPrompt, userMessage, List.of(), null, null);
    }

    private String callMessages(ModelSpec model, String systemPrompt, String userMessage,
                                List<LlmTool> tools) throws IOException {
        return callMessages(model, systemPrompt, userMessage, tools, null, null);
    }

    private String callMessages(ModelSpec model, String systemPrompt, String userMessage,
                                List<LlmTool> tools, String requiredToolName,
                                String toolInputMessage) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("DeepSeek API key is not configured");
        }

        List<LlmTool> activeTools = tools == null ? List.of() : tools;
        StringBuilder executedToolResults = new StringBuilder();
        List<Map<String, Object>> messages = buildMessages(systemPrompt, userMessage, activeTools);
        if (requiredToolName != null && !requiredToolName.isBlank()) {
            executeRequiredTool(messages, requiredToolName, activeTools,
                toolInputMessage == null ? userMessage : toolInputMessage, executedToolResults);
            messages.add(Map.of("role", "user", "content", forcedFinalAnswerPrompt(executedToolResults)));
            JsonNode finalResponse = send(buildRequestBody(model, messages, List.of()));
            return extractText(finalResponse);
        }

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JsonNode response = send(buildRequestBody(model, messages, activeTools, requiredToolName));
            String text = extractText(response);
            if (activeTools.isEmpty()) {
                return text;
            }

            List<JsonNode> toolUses = toolUseBlocks(response);
            if (!toolUses.isEmpty()) {
                appendNativeToolResults(messages, response, toolUses, activeTools, executedToolResults);
                continue;
            }

            List<DsmlToolCall> dsmlToolCalls = dsmlToolCalls(text);
            if (!dsmlToolCalls.isEmpty()) {
                appendDsmlToolResults(messages, text, dsmlToolCalls, activeTools, executedToolResults);
                continue;
            }

            LlmTool degradedTool = ToolCallSupport.detectDegradedToolSelection(text, activeTools);
            if (degradedTool != null) {
                appendDegradedToolResult(messages, text, degradedTool, userMessage, executedToolResults);
                continue;
            }

            return text;
        }

        messages.add(Map.of("role", "user", "content", forcedFinalAnswerPrompt(executedToolResults)));
        JsonNode finalResponse = send(buildRequestBody(model, messages, List.of()));
        String finalText = extractText(finalResponse);
        if (!toolUseBlocks(finalResponse).isEmpty() || containsDsmlToolCalls(finalText)) {
            return fallbackToolDeliverable(executedToolResults);
        }
        return finalText;
    }

    private void appendNativeToolResults(List<Map<String, Object>> messages, JsonNode response,
                                         List<JsonNode> toolUses, List<LlmTool> tools,
                                         StringBuilder executedToolResults) {
        messages.add(Map.of(
            "role", "assistant",
            "content", MAPPER.convertValue(response.path("content"), List.class)
        ));
        List<Map<String, Object>> resultBlocks = toolUses.stream()
            .map(toolUse -> toolResultBlock(toolUse, tools, executedToolResults))
            .toList();
        messages.add(Map.of(
            "role", "user",
            "content", resultBlocks
        ));
    }

    private void appendDsmlToolResults(List<Map<String, Object>> messages, String assistantText,
                                       List<DsmlToolCall> dsmlToolCalls, List<LlmTool> tools,
                                       StringBuilder executedToolResults) {
        messages.add(Map.of("role", "assistant", "content", assistantText));
        messages.add(Map.of("role", "user", "content", dsmlToolResultsMessage(dsmlToolCalls, tools, executedToolResults)));
    }

    Map<String, Object> buildRequestBody(ModelSpec model, String systemPrompt, String userMessage) {
        return buildRequestBody(model, systemPrompt, userMessage, List.of());
    }

    Map<String, Object> buildRequestBody(ModelSpec model, String systemPrompt, String userMessage,
                                         List<LlmTool> tools) {
        return buildRequestBody(model, buildMessages(systemPrompt, userMessage, tools), tools, null);
    }

    Map<String, Object> buildRequestBody(ModelSpec model, String systemPrompt, String userMessage,
                                         List<LlmTool> tools, String requiredToolName) {
        return buildRequestBody(model, buildMessages(systemPrompt, userMessage, tools), tools, requiredToolName);
    }

    private Map<String, Object> buildRequestBody(ModelSpec model, List<Map<String, Object>> messages,
                                                 List<LlmTool> tools) {
        return buildRequestBody(model, messages, tools, null);
    }

    private Map<String, Object> buildRequestBody(ModelSpec model, List<Map<String, Object>> messages,
                                                 List<LlmTool> tools, String requiredToolName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.modelName());
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("thinking", Map.of("type", "disabled"));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(this::toAnthropicTool).toList());
            body.put("tool_choice", toolChoice(requiredToolName));
        }
        return body;
    }

    private Map<String, Object> toolChoice(String requiredToolName) {
        if (requiredToolName == null || requiredToolName.isBlank()) {
            return Map.of("type", "auto");
        }
        return Map.of("type", "tool", "name", requiredToolName);
    }

    private void executeRequiredTool(List<Map<String, Object>> messages, String requiredToolName,
                                     List<LlmTool> tools, String userMessage,
                                     StringBuilder executedToolResults) {
        LlmTool tool = findTool(requiredToolName, tools);
        String arguments = tool == null ? "{}" : ToolCallSupport.defaultArgumentsJson(tool, userMessage);
        String result = tool == null ? "Error: tool not found: " + requiredToolName : tool.call(arguments);
        appendExecutedToolResult(executedToolResults, requiredToolName, tool == null ? "(none)" : tool.name(), arguments, result);
        messages.add(Map.of("role", "assistant", "content", requiredToolName));
        messages.add(Map.of("role", "user", "content", """
            The runtime executed the required tool before asking the model to answer.
            Requested tool: %s
            Arguments: %s
            Result:
            %s
            """.formatted(requiredToolName, arguments, result)));
    }

    private void appendDegradedToolResult(List<Map<String, Object>> messages, String assistantText,
                                          LlmTool tool, String userMessage,
                                          StringBuilder executedToolResults) {
        String argumentsJson = ToolCallSupport.defaultArgumentsJson(tool, userMessage);
        String result = tool.call(argumentsJson);
        appendExecutedToolResult(executedToolResults, assistantText == null ? tool.name() : assistantText.trim(),
            tool.name(), argumentsJson, result);
        messages.add(Map.of("role", "assistant", "content", assistantText == null ? tool.name() : assistantText));
        messages.add(Map.of("role", "user", "content", """
            The runtime resolved your previous plain-text tool selection into a formal tool execution.
            Requested tool: %s
            Arguments: %s
            Result:
            %s

            %s
            """.formatted(tool.name(), argumentsJson, result, ToolCallSupport.finalAnswerInstruction())));
    }

    private List<Map<String, Object>> buildMessages(String systemPrompt, String userMessage, List<LlmTool> tools) {
        List<Map<String, Object>> messages = new ArrayList<>();
        String fullPrompt = systemPrompt == null || systemPrompt.isBlank()
            ? ToolCallSupport.toolUseInstruction(userMessage, tools)
            : systemPrompt + "\n\n" + ToolCallSupport.toolUseInstruction(userMessage, tools);
        messages.add(Map.of("role", "user", "content", fullPrompt + "\n\n" + (userMessage == null ? "" : userMessage)));
        return messages;
    }

    private JsonNode send(Map<String, Object> body) throws IOException {
        Request request = new Request.Builder()
            .url(messagesUrl())
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
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

    private StreamingDeepSeekResult streamMessagesBody(Map<String, Object> body,
                                                       FluxSink<String> sink,
                                                       boolean holdPossibleDsmlText) throws IOException {
        Map<String, Object> streamingBody = new LinkedHashMap<>(body);
        streamingBody.put("stream", true);
        Request request = new Request.Builder()
            .url(messagesUrl())
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(RequestBody.create(MAPPER.writeValueAsBytes(streamingBody), JSON))
            .build();
        Call call = httpClient.newCall(request);

        StringBuilder content = new StringBuilder();
        DeepSeekTextEmitter textEmitter = new DeepSeekTextEmitter(sink, content, holdPossibleDsmlText);
        Map<Integer, DeepSeekToolUseBuilder> toolUseBuilders = new TreeMap<>();
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
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (sink.isCancelled()) {
                    break;
                }
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        handleDeepSeekStreamData(event, data.toString(), textEmitter, toolUseBuilders);
                    }
                    event = null;
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String value = line.substring(5).trim();
                    if ("[DONE]".equals(value)) {
                        break;
                    }
                    data.append(value);
                }
            }
            if (!data.isEmpty() && !sink.isCancelled()) {
                handleDeepSeekStreamData(event, data.toString(), textEmitter, toolUseBuilders);
            }
        } catch (IOException e) {
            if (sink.isCancelled()) {
                return new StreamingDeepSeekResult(content.toString(), toolUses(toolUseBuilders),
                    textEmitter.heldText());
            }
            throw e;
        }
        return new StreamingDeepSeekResult(content.toString(), toolUses(toolUseBuilders), textEmitter.heldText());
    }

    private void handleDeepSeekStreamData(String event, String data, DeepSeekTextEmitter textEmitter,
                                          Map<Integer, DeepSeekToolUseBuilder> toolUseBuilders) throws IOException {
        if (data == null || data.isBlank()) {
            return;
        }
        JsonNode root = MAPPER.readTree(data);
        String type = root.path("type").asText(event == null ? "" : event);
        if ("error".equals(type)) {
            JsonNode error = root.path("error");
            throw new IOException(error.path("message").asText(error.toString()));
        }
        if ("content_block_start".equals(type)) {
            JsonNode block = root.path("content_block");
            int index = root.path("index").asInt(toolUseBuilders.size());
            if ("tool_use".equals(block.path("type").asText(""))) {
                DeepSeekToolUseBuilder builder = toolUseBuilders.computeIfAbsent(index,
                    ignored -> new DeepSeekToolUseBuilder());
                builder.id = block.path("id").asText("");
                builder.name = block.path("name").asText("");
                JsonNode input = block.path("input");
                if (!input.isMissingNode() && !input.isNull() && !input.isEmpty()) {
                    builder.input.append(MAPPER.writeValueAsString(MAPPER.convertValue(input, Object.class)));
                }
            }
            return;
        }
        if (!"content_block_delta".equals(type)) {
            return;
        }

        JsonNode delta = root.path("delta");
        String deltaType = delta.path("type").asText("");
        String text = delta.path("text").asText("");
        if ("text_delta".equals(deltaType) || (!text.isEmpty() && delta.path("partial_json").isMissingNode())) {
            if (!text.isEmpty()) {
                textEmitter.emit(text);
            }
            return;
        }
        String partialJson = delta.path("partial_json").asText("");
        if (!partialJson.isEmpty()) {
            int index = root.path("index").asInt(toolUseBuilders.size());
            DeepSeekToolUseBuilder builder = toolUseBuilders.computeIfAbsent(index,
                ignored -> new DeepSeekToolUseBuilder());
            builder.input.append(partialJson);
        }
    }

    private List<DeepSeekToolUse> toolUses(Map<Integer, DeepSeekToolUseBuilder> builders) {
        List<DeepSeekToolUse> calls = new ArrayList<>();
        for (var entry : builders.entrySet()) {
            DeepSeekToolUseBuilder builder = entry.getValue();
            if (builder.name == null || builder.name.isBlank()) {
                continue;
            }
            String id = builder.id == null || builder.id.isBlank() ? "toolu_" + entry.getKey() : builder.id;
            String input = builder.input.isEmpty() ? "{}" : builder.input.toString();
            calls.add(new DeepSeekToolUse(id, builder.name, input));
        }
        return calls;
    }

    private void appendStreamedNativeToolResults(List<Map<String, Object>> messages, List<DeepSeekToolUse> toolUses,
                                                 List<LlmTool> tools, StringBuilder executedToolResults) {
        List<Map<String, Object>> assistantContent = new ArrayList<>();
        List<Map<String, Object>> resultBlocks = new ArrayList<>();
        for (DeepSeekToolUse call : toolUses) {
            Object input = parseToolInput(call.inputJson());
            assistantContent.add(Map.of(
                "type", "tool_use",
                "id", call.id(),
                "name", call.name(),
                "input", input
            ));

            LlmTool tool = findTool(call.name(), tools);
            String arguments = input instanceof Map<?, ?> ? toJson(input) : call.inputJson();
            String content = tool == null ? "Error: tool not found: " + call.name() : tool.call(arguments);
            appendExecutedToolResult(executedToolResults, call.name(), tool == null ? "(none)" : tool.name(),
                arguments, content);
            resultBlocks.add(Map.of(
                "type", "tool_result",
                "tool_use_id", call.id(),
                "content", content
            ));
        }
        messages.add(Map.of("role", "assistant", "content", assistantContent));
        messages.add(Map.of("role", "user", "content", resultBlocks));
    }

    private Object parseToolInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(inputJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void emitHeldText(StreamingDeepSeekResult result, FluxSink<String> sink) {
        if (result.heldText() != null && !result.heldText().isEmpty() && !sink.isCancelled()) {
            sink.next(result.heldText());
        }
    }

    private Map<String, Object> toAnthropicTool(LlmTool tool) {
        return Map.of(
            "name", tool.name(),
            "description", tool.description() == null ? "" : tool.description(),
            "input_schema", objectSchema(tool.parameters())
        );
    }

    private Map<String, Object> objectSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        Map<String, Object> normalized = new LinkedHashMap<>(schema);
        normalized.putIfAbsent("type", "object");
        return normalized;
    }

    private List<JsonNode> toolUseBlocks(JsonNode root) {
        List<JsonNode> blocks = new ArrayList<>();
        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("tool_use".equals(block.path("type").asText(""))) {
                    blocks.add(block);
                }
            }
        }
        return blocks;
    }

    private Map<String, Object> toolResultBlock(JsonNode toolUse, List<LlmTool> tools,
                                                StringBuilder executedToolResults) {
        String toolName = toolUse.path("name").asText("");
        String arguments = "{}";
        JsonNode input = toolUse.path("input");
        if (!input.isMissingNode() && !input.isNull()) {
            try {
                arguments = MAPPER.writeValueAsString(MAPPER.convertValue(input, Object.class));
            } catch (Exception e) {
                arguments = "{}";
            }
        }
        LlmTool tool = findTool(toolName, tools);
        String content = tool == null ? "Error: tool not found: " + toolName : tool.call(arguments);
        appendExecutedToolResult(executedToolResults, toolName, tool == null ? "(none)" : tool.name(), arguments, content);
        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUse.path("id").asText(""),
            "content", content
        );
    }

    List<DsmlToolCall> dsmlToolCalls(String text) {
        List<DsmlToolCall> calls = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return calls;
        }
        Matcher invokeMatcher = DSML_INVOKE_PATTERN.matcher(text);
        while (invokeMatcher.find()) {
            String toolName = invokeMatcher.group(1).trim();
            String body = invokeMatcher.group(2);
            Map<String, Object> arguments = new LinkedHashMap<>();
            Matcher parameterMatcher = DSML_PARAMETER_PATTERN.matcher(body);
            while (parameterMatcher.find()) {
                arguments.put(parameterMatcher.group(1).trim(), decodeDsmlValue(parameterMatcher.group(2)));
            }
            calls.add(new DsmlToolCall(toolName, arguments));
        }
        return calls;
    }

    boolean containsDsmlToolCalls(String text) {
        return text != null && DSML_INVOKE_PATTERN.matcher(text).find();
    }

    String dsmlToolResultsMessage(List<DsmlToolCall> calls, List<LlmTool> tools) {
        return dsmlToolResultsMessage(calls, tools, new StringBuilder());
    }

    String dsmlToolResultsMessage(List<DsmlToolCall> calls, List<LlmTool> tools,
                                  StringBuilder executedToolResults) {
        StringBuilder output = new StringBuilder("""
            The runtime executed the tool calls below. Use these results to produce the final user-facing deliverable.
            Do not emit DSML tags, tool_calls, or additional tool invocations. If a tool failed, use the available results and state the limitation briefly.

            Tool results:
            """);

        for (int i = 0; i < calls.size(); i++) {
            DsmlToolCall call = calls.get(i);
            LlmTool tool = findTool(call.name(), tools);
            String argumentsJson = toJson(normalizeArguments(tool == null ? call.name() : tool.name(), call.arguments()));
            String result = tool == null ? "Error: tool not found: " + call.name() : tool.call(argumentsJson);
            appendExecutedToolResult(executedToolResults, call.name(), tool == null ? "(none)" : tool.name(), argumentsJson, result);
            output.append("\n")
                .append(i + 1)
                .append(". requestedTool=")
                .append(call.name())
                .append(", resolvedTool=")
                .append(tool == null ? "(none)" : tool.name())
                .append(", arguments=")
                .append(argumentsJson)
                .append("\nResult:\n")
                .append(result)
                .append("\n");
        }
        return output.toString();
    }

    private void appendExecutedToolResult(StringBuilder output, String requestedTool, String resolvedTool,
                                          String argumentsJson, String result) {
        if (output == null) {
            return;
        }
        output.append("\n")
            .append("Tool: ")
            .append(requestedTool)
            .append(" -> ")
            .append(resolvedTool)
            .append("\nArguments: ")
            .append(argumentsJson)
            .append("\nResult:\n")
            .append(result == null ? "" : result)
            .append("\n");
    }

    private String forcedFinalAnswerPrompt(StringBuilder executedToolResults) {
        return """
            Stop calling tools now. Based only on the executed tool results below, write the final deliverable text for the user's task.
            Do not emit DSML tags, JSON tool calls, tool_calls, or more tool requests.
            Return a concise but complete answer.

            Executed tool results:
            %s
            """.formatted(executedToolResults == null ? "" : executedToolResults.toString());
    }

    String fallbackToolDeliverable(StringBuilder executedToolResults) {
        String results = executedToolResults == null ? "" : executedToolResults.toString().trim();
        if (results.isBlank()) {
            return "工具执行已结束，但模型没有生成最终文字结果。";
        }
        return "模型持续请求更多工具调用，系统已停止继续调用。以下是已获得的工具结果，可供 Reviewer 审查和整合：\n"
            + results;
    }

    private LlmTool findTool(String toolName, List<LlmTool> tools) {
        if (toolName == null || tools == null || tools.isEmpty()) {
            return null;
        }
        String normalized = normalizeToolName(toolName);
        LlmTool best = null;
        for (LlmTool candidate : tools) {
            String candidateName = normalizeToolName(candidate.name());
            if (candidate.name().equals(toolName) || candidateName.equals(normalized)) {
                return candidate;
            }
            if (candidateName.contains(normalized) || normalized.contains(candidateName)) {
                best = candidate;
            }
        }
        return best;
    }

    private Map<String, Object> normalizeArguments(String toolName, Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        String name = normalizeToolName(toolName);
        if (name.contains("weather")) {
            copyFirstPresent(normalized, "city", "location", "place", "cityName", "city_name");
        } else if (name.contains("calculator") || name.contains("calc")) {
            copyFirstPresent(normalized, "expression", "expr", "formula", "input", "calculation");
        } else if (name.contains("search") || name.contains("web_search")) {
            copyFirstPresent(normalized, "query", "q", "keyword", "keywords", "searchQuery", "search_query");
        }
        return normalized;
    }

    private void copyFirstPresent(Map<String, Object> arguments, String target, String... aliases) {
        if (arguments.containsKey(target)) {
            return;
        }
        for (String alias : aliases) {
            Object value = arguments.get(alias);
            if (value != null && !String.valueOf(value).isBlank()) {
                arguments.put(target, value);
                return;
            }
        }
    }

    private static String normalizeToolName(String value) {
        return value == null ? "" : value.trim().replaceAll("[.\\-]", "_").toLowerCase(Locale.ROOT);
    }

    private static String decodeDsmlValue(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&");
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    record DsmlToolCall(String name, Map<String, Object> arguments) {
    }

    private record StreamingDeepSeekResult(String content, List<DeepSeekToolUse> toolUses, String heldText) {
    }

    private record DeepSeekToolUse(String id, String name, String inputJson) {
    }

    private static class DeepSeekToolUseBuilder {
        private String id;
        private String name;
        private final StringBuilder input = new StringBuilder();
    }

    private static class DeepSeekTextEmitter {
        private static final List<String> DSML_PREFIXES = List.of("<｜｜DSML", "<||DSML");

        private final FluxSink<String> sink;
        private final StringBuilder content;
        private final boolean holdPossibleDsmlText;
        private final StringBuilder held = new StringBuilder();
        private boolean passthrough;

        private DeepSeekTextEmitter(FluxSink<String> sink, StringBuilder content, boolean holdPossibleDsmlText) {
            this.sink = sink;
            this.content = content;
            this.holdPossibleDsmlText = holdPossibleDsmlText;
        }

        private void emit(String text) {
            content.append(text);
            if (!holdPossibleDsmlText || passthrough) {
                sink.next(text);
                return;
            }

            held.append(text);
            String candidate = held.toString().stripLeading();
            if (candidate.isEmpty() || isDsmlPrefixCandidate(candidate)) {
                return;
            }

            sink.next(held.toString());
            held.setLength(0);
            passthrough = true;
        }

        private String heldText() {
            return held.toString();
        }

        private boolean isDsmlPrefixCandidate(String text) {
            for (String prefix : DSML_PREFIXES) {
                if (prefix.startsWith(text) || text.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    String extractText(JsonNode root) {
        StringBuilder output = new StringBuilder();
        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if (!"text".equals(block.path("type").asText(""))) {
                    continue;
                }
                String text = block.path("text").asText("");
                if (!text.isBlank()) {
                    output.append(text);
                }
            }
        }
        if (!output.isEmpty()) {
            return output.toString();
        }
        return root.path("text").asText("");
    }

    private String messagesUrl() {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (normalized.endsWith("/messages")) {
            return normalized;
        }
        return normalized + "/messages";
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.deepseek.com/anthropic";
        }
        return value.trim();
    }

    private String unsupportedMultimodalMessage() {
        return "[Error] 当前 DeepSeek Provider 暂不支持多模态图片输入";
    }
}
