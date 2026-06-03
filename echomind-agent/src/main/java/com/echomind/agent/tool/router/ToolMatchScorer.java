package com.echomind.agent.tool.router;

import com.echomind.agent.tool.core.Tool;
import com.echomind.agent.tool.core.ToolRoutingMetadata;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 基于工具显式 metadata（keywords、aliases、tags、name）给候选工具打分。
 *
 * <p>匹配流程：用户消息 → 归一化 → 逐工具打分 → 过滤弱匹配 → 按分数降序返回。
 * 只做纯字符串包含匹配，不调用模型，不猜测参数。</p>
 */
final class ToolMatchScorer {

    private static final int STRONG_MATCH_SCORE = 4;

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "with", "from", "that", "this", "you", "your", "are",
        "use", "used", "using", "into", "onto", "when", "need", "needs", "tool",
        "tools", "data", "text", "input", "output", "string", "object", "type",
        "的", "了", "和", "与", "或", "在", "是", "吗", "呢", "吧", "啊",
        "我", "你", "他", "她", "它", "我们", "你们", "他们", "这个", "那个",
        "一下", "帮我", "请", "麻烦", "需要", "想要", "可以", "能否", "帮忙",
        "工具", "数据", "文本", "内容", "输入", "输出"
    );

    List<Tool> match(String userMessage, Collection<Tool> candidateTools) {
        if (userMessage == null || userMessage.isBlank() || candidateTools == null || candidateTools.isEmpty()) {
            return List.of();
        }
        SearchText message = SearchText.from(userMessage);
        return candidateTools.stream()
            .map(tool -> new ToolMatch(tool, score(message, tool)))
            .filter(match -> match.score() >= STRONG_MATCH_SCORE)
            .sorted(Comparator.comparingInt(ToolMatch::score).reversed())
            .map(ToolMatch::tool)
            .toList();
    }

    private int score(SearchText message, Tool tool) {
        int score = 0;
        score += scoreTerms(message, tool.keywords(), 8, TermSource.AUTHORED);

        for (var entry : ToolRoutingMetadata.aliases(tool).entrySet()) {
            score += scoreTerm(message, entry.getKey(), 6, TermSource.METADATA);
            score += scoreTerms(message, entry.getValue(), 8, TermSource.AUTHORED);
        }

        score += scoreTerm(message, tool.name(), 6, TermSource.METADATA);
        score += scoreTerms(message, splitIdentifier(tool.name()), 4, TermSource.METADATA);
        for (String tag : ToolRoutingMetadata.terms(tool.tags())) {
            score += scoreTerm(message, tag, 5, TermSource.METADATA);
        }

        return score;
    }

    private int scoreTerms(SearchText message, Collection<String> terms, int points, TermSource source) {
        if (terms == null || terms.isEmpty()) {
            return 0;
        }
        int score = 0;
        Set<String> seen = new HashSet<>();
        for (String term : terms) {
            String normalized = normalizeTerm(term, source);
            if (normalized != null && seen.add(normalized) && containsTerm(message, normalized)) {
                score += points;
            }
        }
        return score;
    }

    private int scoreTerm(SearchText message, String term, int points, TermSource source) {
        String normalized = normalizeTerm(term, source);
        return normalized != null && containsTerm(message, normalized) ? points : 0;
    }

    private boolean containsTerm(SearchText message, String term) {
        if (containsCjk(term)) {
            return message.compactCjk().contains(term);
        }
        return message.normalized().contains(term);
    }

    private String normalizeTerm(String term, TermSource source) {
        if (term == null) {
            return null;
        }
        String normalized = normalizeText(term);
        String comparable = containsCjk(normalized) ? compactForCjk(normalized) : normalized;
        if (comparable.isEmpty() || STOP_WORDS.contains(normalized) || STOP_WORDS.contains(comparable)) {
            return null;
        }
        if (containsCjk(comparable)) {
            return cjkCharCount(comparable) >= source.minCjkChars ? comparable : null;
        }
        return comparable.length() >= 3 ? comparable : null;
    }

    private List<String> splitIdentifier(String text) {
        if (text == null) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String part : text.split("[\\s_\\-./:]+")) {
            String normalized = normalizeTerm(part, TermSource.METADATA);
            if (normalized != null) {
                terms.add(normalized);
            }
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

    private int cjkCharCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                count++;
            }
        }
        return count;
    }

    private static String normalizeText(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .trim()
            .replaceAll("\\s+", " ");
    }

    private static String compactForCjk(String text) {
        StringBuilder compact = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!isIgnorableSeparator(ch)) {
                compact.append(ch);
            }
        }
        return compact.toString();
    }

    private static boolean isIgnorableSeparator(char ch) {
        if (Character.isWhitespace(ch)) {
            return true;
        }
        return switch (Character.getType(ch)) {
            case Character.CONNECTOR_PUNCTUATION,
                 Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION,
                 Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private enum TermSource {
        AUTHORED(1),
        METADATA(2);

        private final int minCjkChars;

        TermSource(int minCjkChars) {
            this.minCjkChars = minCjkChars;
        }
    }

    private record SearchText(String normalized, String compactCjk) {
        static SearchText from(String text) {
            String normalized = normalizeText(text);
            return new SearchText(normalized, compactForCjk(normalized));
        }
    }

    private record ToolMatch(Tool tool, int score) {
    }
}