package com.echomind.agent.tool;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scores tools against a user message using tool metadata and schema hints.
 */
final class ToolMatchScorer {

    private static final int STRONG_MATCH_SCORE = 4;

    private static final Map<String, List<String>> TAG_ALIASES = Map.of(
        "write", List.of("写入", "写", "保存", "创建"),
        "read", List.of("读取", "读", "查看", "阅读"),
        "search", List.of("搜索", "查询", "查找", "搜一下", "搜", "查一下", "上网查", "联网查"),
        "file", List.of("文件", "文档"),
        "weather", List.of("天气", "气温"),
        "calculate", List.of("计算", "运算", "算"),
        "lookup", List.of("查询", "查找", "搜索"),
        "find", List.of("查找", "搜索", "找"),
        "web", List.of("网络", "网页", "上网", "在线", "链接", "网址", "url", "http", "https", "打开链接")
    );

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "with", "from", "that", "this", "you", "your", "are",
        "use", "used", "using", "into", "onto", "when", "need", "needs", "tool",
        "tools", "data", "text", "input", "output", "string", "object", "type"
    );

    private final ToolCompatibilityPolicy compatibilityPolicy;

    ToolMatchScorer(ToolCompatibilityPolicy compatibilityPolicy) {
        this.compatibilityPolicy = compatibilityPolicy;
    }

    List<Tool> match(String userMessage, Collection<Tool> candidateTools) {
        if (userMessage == null || userMessage.isBlank() || candidateTools == null || candidateTools.isEmpty()) {
            return List.of();
        }
        String lowerMsg = userMessage.toLowerCase(Locale.ROOT);
        return candidateTools.stream()
            .map(tool -> new ToolMatch(tool, score(lowerMsg, tool)))
            .filter(match -> match.score() >= STRONG_MATCH_SCORE)
            .sorted(Comparator.comparingInt(ToolMatch::score).reversed())
            .map(ToolMatch::tool)
            .toList();
    }

    private int score(String lowerMsg, Tool tool) {
        int score = 0;
        List<URI> urls = compatibilityPolicy.extractHttpUrls(lowerMsg);
        if (!urls.isEmpty() && !compatibilityPolicy.isCompatibleWithUrls(tool, urls)) {
            return 0;
        }

        score += compatibilityPolicy.scoreUrlIntent(urls, tool);
        score += scoreTerms(lowerMsg, tool.keywords(), 8);

        for (var entry : ToolRoutingMetadata.aliases(tool).entrySet()) {
            score += scoreTerm(lowerMsg, entry.getKey(), 6);
            score += scoreTerms(lowerMsg, entry.getValue(), 8);
        }

        score += scoreTerm(lowerMsg, tool.name(), 6);
        score += scoreTerms(lowerMsg, splitIdentifier(tool.name()), 4);
        for (String tag : ToolRoutingMetadata.terms(tool.tags())) {
            score += scoreTerm(lowerMsg, tag, 5);
            score += scoreTerms(lowerMsg, TAG_ALIASES.get(tag.toLowerCase(Locale.ROOT)), 5);
        }

        score += scoreTerms(lowerMsg, schemaTerms(tool.parameterSchema()), 2);
        score += scoreTerms(lowerMsg, textTerms(tool.description()), 2);
        return score;
    }

    private int scoreTerms(String lowerMsg, Collection<String> terms, int points) {
        if (terms == null || terms.isEmpty()) {
            return 0;
        }
        int score = 0;
        Set<String> seen = new HashSet<>();
        for (String term : terms) {
            String normalized = normalizeTerm(term);
            if (normalized != null && seen.add(normalized) && containsTerm(lowerMsg, normalized)) {
                score += points;
            }
        }
        return score;
    }

    private int scoreTerm(String lowerMsg, String term, int points) {
        String normalized = normalizeTerm(term);
        return normalized != null && containsTerm(lowerMsg, normalized) ? points : 0;
    }

    private boolean containsTerm(String lowerMsg, String term) {
        return lowerMsg.contains(term);
    }

    private String normalizeTerm(String term) {
        if (term == null) {
            return null;
        }
        String normalized = term.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || STOP_WORDS.contains(normalized)) {
            return null;
        }
        if (containsCjk(normalized)) {
            return normalized.length() >= 1 ? normalized : null;
        }
        return normalized.length() >= 3 ? normalized : null;
    }

    private List<String> splitIdentifier(String text) {
        if (text == null) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String part : text.split("[\\s_\\-./:]+")) {
            String normalized = normalizeTerm(part);
            if (normalized != null) {
                terms.add(normalized);
            }
        }
        return terms;
    }

    private List<String> textTerms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String part : text.toLowerCase(Locale.ROOT).split("[\\s，。！？,.!?;；:：()（）\\[\\]{}<>]+")) {
            String normalized = normalizeTerm(part);
            if (normalized != null) {
                terms.add(normalized);
                if (containsCjk(normalized) && normalized.length() > 2) {
                    terms.addAll(cjkNgrams(normalized));
                }
            }
        }
        return terms;
    }

    private List<String> schemaTerms(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        Object propertiesObj = schema.get("properties");
        if (propertiesObj instanceof Map<?, ?> properties) {
            for (var entry : properties.entrySet()) {
                terms.add(String.valueOf(entry.getKey()));
                Object propObj = entry.getValue();
                if (propObj instanceof Map<?, ?> propDef) {
                    Object description = propDef.get("description");
                    if (description != null) {
                        terms.addAll(textTerms(String.valueOf(description)));
                    }
                    Object enumObj = propDef.get("enum");
                    if (enumObj instanceof List<?> enumValues) {
                        for (Object enumValue : enumValues) {
                            terms.add(String.valueOf(enumValue));
                            List<String> aliases = ToolRoutingMetadata.ENUM_ALIASES
                                .get(String.valueOf(enumValue).toLowerCase(Locale.ROOT));
                            if (aliases != null) {
                                terms.addAll(aliases);
                            }
                        }
                    }
                }
            }
        }
        Object schemaDescription = schema.get("description");
        if (schemaDescription != null) {
            terms.addAll(textTerms(String.valueOf(schemaDescription)));
        }
        return terms;
    }

    private boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private List<String> cjkNgrams(String text) {
        List<String> terms = new ArrayList<>();
        for (int size = 2; size <= 3; size++) {
            for (int i = 0; i + size <= text.length(); i++) {
                terms.add(text.substring(i, i + size));
            }
        }
        return terms;
    }

    private record ToolMatch(Tool tool, int score) {
    }
}
