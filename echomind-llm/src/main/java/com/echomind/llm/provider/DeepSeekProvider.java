package com.echomind.llm.provider;

import com.echomind.llm.router.ModelSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek Anthropic-compatible Provider.
 *
 * <p>DeepSeek 的 Anthropic 兼容接口可能返回 {@code thinking} content block。
 * 当前 Spring AI Anthropic M4 版本不认识这个 block 类型，因此这里直接用宽松 JSON
 * 调用，只抽取 {@code text} block，忽略推理块。</p>
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
    private final OkHttpClient httpClient;

    public DeepSeekProvider(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build());
    }

    DeepSeekProvider(String baseUrl, String apiKey, OkHttpClient httpClient) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
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
    public String chat(ModelSpec model, String systemPrompt, String userMessage) {
        try {
            return callMessages(model, systemPrompt, userMessage);
        } catch (Exception e) {
            log.error("DeepSeek API call failed", e);
            return "[Error] " + e.getMessage();
        }
    }

    @Override
    public Flux<String> stream(ModelSpec model, String systemPrompt, String userMessage) {
        return Flux.just(chat(model, systemPrompt, userMessage));
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
        try {
            return callMessages(model, systemPrompt, userMessage, tools);
        } catch (Exception e) {
            log.error("DeepSeek tool API call failed", e);
            return "[Error] " + e.getMessage();
        }
    }

    private String callMessages(ModelSpec model, String systemPrompt, String userMessage) throws IOException {
        return callMessages(model, systemPrompt, userMessage, List.of());
    }

    private String callMessages(ModelSpec model, String systemPrompt, String userMessage,
                                List<LlmTool> tools) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("DeepSeek API key is not configured");
        }

        List<LlmTool> activeTools = tools == null ? List.of() : tools;
        StringBuilder executedToolResults = new StringBuilder();
        List<Map<String, Object>> messages = buildMessages(systemPrompt, userMessage);

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JsonNode response = send(buildRequestBody(model, messages, activeTools));
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
        return buildRequestBody(model, buildMessages(systemPrompt, userMessage), tools);
    }

    private Map<String, Object> buildRequestBody(ModelSpec model, List<Map<String, Object>> messages,
                                                 List<LlmTool> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.modelName());
        body.put("messages", messages);
        body.put("max_tokens", 4096);
        body.put("thinking", Map.of("type", "disabled"));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(this::toAnthropicTool).toList());
            body.put("tool_choice", Map.of("type", "auto"));
        }
        return body;
    }

    private List<Map<String, Object>> buildMessages(String systemPrompt, String userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "user", "content", systemPrompt + "\n\n" + (userMessage == null ? "" : userMessage)));
        } else {
            messages.add(Map.of("role", "user", "content", userMessage == null ? "" : userMessage));
        }
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
        String canonical = canonicalToolName(normalized);
        return tools.stream()
            .filter(candidate -> {
                String candidateName = normalizeToolName(candidate.name());
                return candidate.name().equals(toolName)
                    || candidateName.equals(normalized)
                    || candidateName.equals(canonical);
            })
            .findFirst()
            .orElse(null);
    }

    private Map<String, Object> normalizeArguments(String toolName, Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        String canonical = canonicalToolName(normalizeToolName(toolName));
        if ("weather_query".equals(canonical)) {
            copyFirstPresent(normalized, "city", "location", "place", "cityName", "city_name");
        } else if ("calculator".equals(canonical)) {
            copyFirstPresent(normalized, "expression", "expr", "formula", "input", "calculation");
        } else if ("web_search".equals(canonical)) {
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

    private static String canonicalToolName(String normalized) {
        return switch (normalized) {
            case "calculate", "math_calculate" -> "calculator";
            case "weather", "forecast", "weather_forecast" -> "weather_query";
            case "search", "web" -> "web_search";
            default -> normalized;
        };
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
}
