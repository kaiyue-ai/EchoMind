package com.echomind.agent.tool.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** EchoMind-side routing metadata for a tool exposed by an external MCP server. */
public record ExternalMcpToolMetadata(
    List<String> tags, // 工具的标签，用于分类和搜索
    List<String> keywords, // 关键词
    Map<String, List<String>> aliases // 工具的别名，用于调用时的替代名称
) {

    private static final ExternalMcpToolMetadata EMPTY =
        new ExternalMcpToolMetadata(List.of(), List.of(), Map.of());

    public ExternalMcpToolMetadata {
        tags = cleanTerms(tags);
        keywords = cleanTerms(keywords);
        aliases = cleanAliases(aliases);
    }

    public static ExternalMcpToolMetadata empty() {
        return EMPTY;
    }

    private static List<String> cleanTerms(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            String normalized = clean(value);
            if (normalized != null && !cleaned.contains(normalized)) {
                cleaned.add(normalized);
            }
        }
        return List.copyOf(cleaned);
    }

    private static Map<String, List<String>> cleanAliases(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> cleaned = new LinkedHashMap<>();
        values.forEach((key, aliases) -> {
            String normalizedKey = clean(key);
            List<String> normalizedAliases = cleanTerms(aliases);
            if (normalizedKey != null && !normalizedAliases.isEmpty()) {
                cleaned.put(normalizedKey, normalizedAliases);
            }
        });
        return Collections.unmodifiableMap(cleaned);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
