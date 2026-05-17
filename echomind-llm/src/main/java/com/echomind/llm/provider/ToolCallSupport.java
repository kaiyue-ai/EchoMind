package com.echomind.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider-level helper for strong tool-use prompting and degraded tool-call fallback.
 *
 * <p>This lives in the LLM module so native-tool providers can share the same behavior
 * without depending on Agent runtime classes.</p>
 */
final class ToolCallSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}(?:T\\d{2}:\\d{2}(?::\\d{2})?)?\\b");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("-?\\d+");

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
        String toolName = normalizeToolName(tool.name());
        String primaryText = normalizePrimaryText(userMessage);
        applyToolSpecificDefaults(toolName, properties, userMessage, primaryText, params);

        Set<String> requiredNames = requiredNames(schema.get("required"));
        for (String requiredName : requiredNames) {
            if (params.containsKey(requiredName)) {
                continue;
            }
            Object value = defaultValueFor(requiredName, asMap(properties.get(requiredName)),
                userMessage, primaryText, true);
            if (value != null) {
                params.put(requiredName, value);
            }
        }

        if (!properties.isEmpty()) {
            for (var entry : properties.entrySet()) {
                String key = entry.getKey();
                if (params.containsKey(key) || requiredNames.contains(key)) {
                    continue;
                }
                Map<String, Object> propDef = asMap(entry.getValue());
                Object value = defaultValueFor(key, propDef, userMessage, primaryText, false);
                if (value != null) {
                    params.put(key, value);
                }
            }
        }

        if (params.isEmpty() && properties.isEmpty()) {
            params.put("query", primaryText);
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
            || "keyword".equalsIgnoreCase(key)
            || "keywords".equalsIgnoreCase(key);
    }

    private static Object defaultValueFor(String key, Map<String, Object> propDef,
                                          String userMessage, String primaryText,
                                          boolean required) {
        String lowerKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        Object enumObj = propDef.get("enum");
        if (enumObj instanceof List<?> enumValues) {
            return matchEnum(normalizeText(userMessage), enumValues);
        }
        String type = String.valueOf(propDef.getOrDefault("type", "string")).toLowerCase(Locale.ROOT);
        if ("url".equals(lowerKey) || lowerKey.endsWith("url")) {
            return firstUrl(userMessage);
        }
        if ("boolean".equals(type)) {
            return inferBoolean(userMessage, lowerKey);
        }
        if ("integer".equals(type) || "number".equals(type)) {
            return inferInteger(userMessage, lowerKey);
        }
        if (isContentLike(lowerKey) || required) {
            return primaryText;
        }
        return null;
    }

    private static void applyToolSpecificDefaults(String toolName, Map<String, Object> properties,
                                                  String userMessage, String primaryText,
                                                  Map<String, Object> params) {
        if (toolName.contains("date") || toolName.contains("calendar") || toolName.contains("weekday")) {
            applyDateDefaults(userMessage, params);
            return;
        }
        if (toolName.contains("nowcoder") || toolName.contains("interview")) {
            String url = firstUrl(userMessage);
            if (url != null && properties.containsKey("url")) {
                params.put("url", url);
                return;
            }
            if (properties.containsKey("keyword")) {
                params.put("keyword", normalizeInterviewKeyword(primaryText));
            }
            if (properties.containsKey("random")) {
                params.put("random", true);
            }
            return;
        }
        if (toolName.contains("weather") && properties.containsKey("city")) {
            params.put("city", normalizeWeatherCity(primaryText));
            return;
        }
        if ((toolName.contains("calculator") || toolName.contains("calc")) && properties.containsKey("expression")) {
            params.put("expression", primaryText);
            return;
        }
        if (toolName.contains("web_search") && properties.containsKey("query")) {
            params.put("query", primaryText);
        }
    }

    private static void applyDateDefaults(String userMessage, Map<String, Object> params) {
        String date = firstIsoDate(userMessage);
        if (date != null) {
            params.put("date", date);
        }
        Integer offset = dateOffset(userMessage);
        if (offset != null) {
            params.put("offsetDays", offset);
        }
        String lower = normalizeText(userMessage);
        if (lower.contains("english") || lower.contains("英文") || lower.contains("en-us")) {
            params.put("locale", "en-US");
        }
    }

    private static Set<String> requiredNames(Object required) {
        if (!(required instanceof List<?> requiredList) || requiredList.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : requiredList) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static String firstUrl(String userMessage) {
        Matcher matcher = URL_PATTERN.matcher(userMessage == null ? "" : userMessage);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group()
            .replaceAll("[，。！？；、\\s)）>]+$", "");
    }

    private static String firstIsoDate(String userMessage) {
        Matcher matcher = ISO_DATE_PATTERN.matcher(userMessage == null ? "" : userMessage);
        return matcher.find() ? matcher.group() : null;
    }

    private static Integer dateOffset(String userMessage) {
        String lower = normalizeText(userMessage);
        if (lower.contains("后天") || lower.contains("day after tomorrow")) {
            return 2;
        }
        if (lower.contains("明天") || lower.contains("tomorrow")) {
            return 1;
        }
        if (lower.contains("前天") || lower.contains("day before yesterday")) {
            return -2;
        }
        if (lower.contains("昨天") || lower.contains("yesterday")) {
            return -1;
        }
        return null;
    }

    private static Boolean inferBoolean(String userMessage, String key) {
        String lower = normalizeText(userMessage);
        if (lower.contains("false") || lower.contains("否") || lower.contains("不要") || lower.contains("不需要")) {
            return false;
        }
        if (lower.contains("true") || lower.contains("是") || lower.contains("需要")) {
            return true;
        }
        if (key.contains("html") && lower.contains("html")) {
            return true;
        }
        if (key.contains("random") && (lower.contains("随机") || lower.contains("random"))) {
            return true;
        }
        return null;
    }

    private static Integer inferInteger(String userMessage, String key) {
        if ("offsetdays".equals(key)) {
            return dateOffset(userMessage);
        }
        Matcher matcher = INTEGER_PATTERN.matcher(userMessage == null ? "" : userMessage);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeInterviewKeyword(String primaryText) {
        String lower = normalizeText(primaryText);
        if (lower.contains("java")) {
            return "Java";
        }
        if (lower.contains("spring")) {
            return "Spring";
        }
        if (lower.contains("mysql")) {
            return "MySQL";
        }
        if (lower.contains("后端")) {
            return "后端";
        }
        return primaryText == null || primaryText.isBlank() ? "Java" : primaryText;
    }

    private static String normalizeWeatherCity(String primaryText) {
        String value = primaryText == null ? "" : primaryText.trim();
        return value
            .replace("天气预报", "")
            .replace("天气", "")
            .replace("气温", "")
            .replace("温度", "")
            .trim();
    }

    private static String normalizePrimaryText(String userMessage) {
        String raw = userMessage == null ? "" : userMessage.trim();
        if (raw.isBlank()) {
            return "";
        }

        String stripped = raw
            .replaceFirst("^(请)?(帮我)?(查询|搜索一下|搜一下|搜索|查一下|查找|联网搜索|打开链接|读取网页|看看链接)\\s*", "")
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
