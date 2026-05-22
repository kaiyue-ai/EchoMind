package com.echomind.agent.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds best-effort tool parameters from a user message for direct tool calls.
 */
final class ToolParameterExtractor {

    private static final Pattern PATH_PATTERN = Pattern.compile(
        "([a-zA-Z]:[\\\\/][.\\w\\\\/-]+|[.~/][.\\w/-]*|[\\w.-]+\\.[a-zA-Z]{1,6})"
    );

    private static final String[] CONTENT_PREFIXES = {
        "内容是", "内容为", "content:", "content=", "content：", "内容是：",
        "搜索", "搜索：", "query:", "query=", "查询", "查找", "搜索一下",
        "内容:", "内容：", "写入:", "写入：", "写入"
    };

    private static final String[] END_MARKERS = {"内容", "content", "写入", "搜索"};

    @SuppressWarnings("unchecked")
    Map<String, Object> buildParams(Tool tool, String userMessage) {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> schema = tool.parameterSchema();
        if (schema == null) {
            params.put("query", userMessage);
            return params;
        }

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties != null) {
            for (var entry : properties.entrySet()) {
                String key = entry.getKey();
                Map<String, Object> propDef = (Map<String, Object>) entry.getValue();

                Object enumObj = propDef.get("enum");
                if (enumObj instanceof List<?> enumValues) {
                    String matched = matchEnum(userMessage.toLowerCase(), enumValues);
                    if (matched != null) {
                        params.put(key, matched);
                        continue;
                    }
                }

                if (key.equalsIgnoreCase("path")) {
                    params.put(key, defaultedPath(userMessage));
                    continue;
                }

                if (key.equalsIgnoreCase("content") || key.equalsIgnoreCase("query")) {
                    String extracted = extractContent(userMessage);
                    if (extracted != null) {
                        params.put(key, extracted);
                        continue;
                    }
                }

                params.put(key, userMessage);
            }
        }

        Object required = schema.get("required");
        if (required instanceof List && !((List<?>) required).isEmpty()) {
            String primary = String.valueOf(((List<?>) required).get(0));
            params.putIfAbsent(primary, userMessage);
        }

        if (params.isEmpty()) {
            params.put("query", userMessage);
        }
        return params;
    }

    private String matchEnum(String lowerMsg, List<?> enumValues) {
        for (Object ev : enumValues) {
            String val = String.valueOf(ev).toLowerCase();
            if (lowerMsg.contains(val)) return String.valueOf(ev);
            List<String> aliases = ToolRoutingMetadata.ENUM_ALIASES.get(val);
            if (aliases != null) {
                for (String alias : aliases) {
                    if (lowerMsg.contains(alias)) return String.valueOf(ev);
                }
            }
        }
        return null;
    }

    private String defaultedPath(String message) {
        String extractedPath = extractPath(message);
        if (extractedPath == null) {
            return "./data/";
        }
        if (!extractedPath.contains("/") && !extractedPath.contains("\\") && !extractedPath.contains(":")) {
            return "./data/" + extractedPath;
        }
        return extractedPath;
    }

    private String extractPath(String message) {
        Matcher m = PATH_PATTERN.matcher(message);
        return m.find() ? m.group() : null;
    }

    private String extractContent(String message) {
        for (String prefix : CONTENT_PREFIXES) {
            int idx = message.indexOf(prefix);
            if (idx >= 0) {
                String after = message.substring(idx + prefix.length()).trim();
                after = after.replaceFirst("^[：:，,\\s]+", "");
                if (!after.isEmpty()) return after;
            }
        }
        for (String marker : END_MARKERS) {
            int idx = message.lastIndexOf(marker);
            if (idx >= 0) {
                String after = message.substring(idx + marker.length()).trim();
                after = after.replaceFirst("^[是为:：,，\\s]+", "");
                if (after.length() > 1) return after;
            }
        }
        return null;
    }
}
