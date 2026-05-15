package com.echomind.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Provider-level helper for strong tool-use prompting and degraded tool-call fallback.
 *
 * <p>This lives in the LLM module so native-tool providers can share the same behavior
 * without depending on Agent runtime classes.</p>
 */
final class ToolCallSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallSupport() {
    }

    static String toolUseInstruction(String userMessage, List<LlmTool> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            Tool use rules:
            - If a tool is needed, you must emit a formal tool call. Do not reply with only a tool name.
            - Every tool call must include complete arguments that satisfy the tool schema.
            - For search-style tools such as web_search, always provide "query".
            - If the user input is already a URL, keyword query, or phrasing like "查询 xxx" / "搜索 xxx", pass that content directly into the tool arguments.
            - After tool results are returned, stop emitting tool-call protocol text and write the final natural-language answer.

            Available tools:
            """);
        for (LlmTool tool : tools == null ? List.<LlmTool>of() : tools) {
            sb.append("- ")
                .append(tool.name())
                .append(": ")
                .append(tool.description() == null ? "" : tool.description())
                .append(" | schema=")
                .append(toJson(tool.parameters()))
                .append("\n");
        }
        if (userMessage != null && !userMessage.isBlank()) {
            sb.append("\nOriginal user input:\n").append(userMessage.trim()).append("\n");
        }
        return sb.toString().trim();
    }

    static LlmTool detectDegradedToolSelection(String text, List<LlmTool> tools) {
        if (text == null || text.isBlank() || tools == null || tools.isEmpty()) {
            return null;
        }
        String normalized = normalizeToolOnlyText(text);
        if (normalized == null) {
            return null;
        }
        for (LlmTool tool : tools) {
            if (tool == null || tool.name() == null) {
                continue;
            }
            String toolName = normalizeToolName(tool.name());
            if (toolName.equals(normalized) || denormalizeToolName(toolName).equals(normalized)) {
                return tool;
            }
        }
        return null;
    }

    static String defaultArgumentsJson(LlmTool tool, String userMessage) {
        return toJson(defaultArguments(tool, userMessage));
    }

    static Map<String, Object> defaultArguments(LlmTool tool, String userMessage) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> schema = tool == null ? null : tool.parameters();
        if (schema == null) {
            params.put("query", normalizePrimaryText(userMessage));
            return params;
        }

        Map<String, Object> properties = asMap(schema.get("properties"));
        if (!properties.isEmpty()) {
            for (var entry : properties.entrySet()) {
                String key = entry.getKey();
                Map<String, Object> propDef = asMap(entry.getValue());

                Object enumObj = propDef.get("enum");
                if (enumObj instanceof List<?> enumValues) {
                    String matched = matchEnum(normalizeText(userMessage), enumValues);
                    if (matched != null) {
                        params.put(key, matched);
                        continue;
                    }
                }

                if (isContentLike(key)) {
                    params.put(key, normalizePrimaryText(userMessage));
                    continue;
                }

                params.put(key, userMessage);
            }
        }

        Object required = schema.get("required");
        if (required instanceof List<?> requiredList && !requiredList.isEmpty()) {
            String primary = String.valueOf(requiredList.get(0));
            params.putIfAbsent(primary, normalizePrimaryText(userMessage));
        }

        if (params.isEmpty()) {
            params.put("query", normalizePrimaryText(userMessage));
        }
        return params;
    }

    static String finalAnswerInstruction() {
        return """
            Tool results are available above.
            Do not emit tool names, DSML tags, JSON tool calls, or any further tool-call protocol.
            Write only the final natural-language answer for the user.
            """;
    }

    private static boolean isContentLike(String key) {
        return "query".equalsIgnoreCase(key)
            || "content".equalsIgnoreCase(key)
            || "input".equalsIgnoreCase(key)
            || "text".equalsIgnoreCase(key)
            || "url".equalsIgnoreCase(key);
    }

    private static String normalizePrimaryText(String userMessage) {
        String raw = userMessage == null ? "" : userMessage.trim();
        if (raw.isBlank()) {
            return "";
        }

        String stripped = raw
            .replaceFirst("^(请)?(帮我)?(查询|搜索一下|搜索|查一下|查找|联网搜索)\\s*", "")
            .replaceFirst("^[：:，,\\s]+", "")
            .trim();
        if (!stripped.isBlank()) {
            return stripped;
        }
        return raw;
    }

    private static String matchEnum(String lowerMsg, List<?> enumValues) {
        for (Object ev : enumValues) {
            String val = String.valueOf(ev).toLowerCase(Locale.ROOT);
            if (lowerMsg.contains(val)) {
                return String.valueOf(ev);
            }
        }
        return null;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeToolOnlyText(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.contains("\n")) {
            return null;
        }
        String normalized = trimmed
            .replace('`', ' ')
            .replace('\"', ' ')
            .replace('\'', ' ')
            .trim()
            .toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_.\\-\\s]+")) {
            return null;
        }
        if (normalized.contains(" ")) {
            return null;
        }
        return normalizeToolName(normalized);
    }

    private static String normalizeToolName(String value) {
        return value == null ? "" : value.trim().replaceAll("[.\\-]", "_").toLowerCase(Locale.ROOT);
    }

    private static String denormalizeToolName(String value) {
        return value == null ? "" : value.replace('_', '-');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return normalized;
        }
        return Map.of();
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
