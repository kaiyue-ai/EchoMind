package com.echomind.agent.tool;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Locale;

final class ToolRoutingMetadata {

    static final Map<String, List<String>> ENUM_ALIASES = Map.of(
        "read", List.of("读取", "读", "查看", "阅读", "read", "看"),
        "write", List.of("写入", "写", "保存", "创建", "write", "存"),
        "list", List.of("列出", "列表", "目录", "显示所有", "list", "ls", "看看")
    );

    private ToolRoutingMetadata() {
    }

    static String searchableText(Tool tool) {
        if (tool == null) {
            return "";
        }
        List<String> terms = new ArrayList<>();
        addTerm(terms, tool.name());
        addTerm(terms, tool.sourceId());
        addTerms(terms, tool.tags());
        addTerms(terms, tool.keywords());
        for (var entry : aliases(tool).entrySet()) {
            addTerm(terms, entry.getKey());
            addTerms(terms, entry.getValue());
        }
        return String.join(" ", terms).toLowerCase(Locale.ROOT);
    }

    static boolean containsAny(String lowerText, Collection<String> terms) {
        if (lowerText == null || lowerText.isBlank() || terms == null || terms.isEmpty()) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && lowerText.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static Collection<String> terms(Collection<String> values) {
        return values == null ? List.of() : values;
    }

    static Map<String, List<String>> aliases(Tool tool) {
        if (tool == null || tool.aliases() == null) {
            return Map.of();
        }
        return tool.aliases();
    }

    static String schemaText(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return "";
        }
        return String.valueOf(schema);
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void addTerms(List<String> terms, Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addTerm(terms, value);
        }
    }

    private static void addTerm(List<String> terms, String value) {
        if (value != null && !value.isBlank()) {
            terms.add(value);
        }
    }
}
