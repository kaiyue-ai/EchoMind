package com.echomind.agent.pipeline.planning;

import com.echomind.common.model.MemoryDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析主模型附带的隐藏记忆决策。
 *
 * <p>主模型仍然只调用一次：先输出给用户看的正文，再追加内部 JSON。解析器负责剥离内部
 * marker，用户永远只看到正文。只接受严格 boolean；解析失败时保守降级为异步处理事实和画像。</p>
 */
public class MemoryDecisionParser {

    public static final String START_MARKER = "<ECHOMIND_MEMORY_DECISION>";
    public static final String END_MARKER = "</ECHOMIND_MEMORY_DECISION>";
    private static final List<String> START_MARKERS = List.of(
        START_MARKER,
        "<ECHOMIMD_MEMORY_DECISION>",
        "<ECHOMIM_MEMORY_DECISION>"
    );
    private static final Pattern DECISION_BLOCK = Pattern.compile(
        "<\\s*(?:ECHOMIND_MEMORY_DECISION|ECHOMIMD_MEMORY_DECISION|ECHOMIM_MEMORY_DECISION)\\s*>\\s*(.*?)\\s*</\\s*(?:ECHOMIND_MEMORY_DECISION|ECHOMIMD_MEMORY_DECISION|ECHOMIM_MEMORY_DECISION)\\s*>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final int MARKER_TAIL_KEEP = START_MARKERS.stream()
        .mapToInt(String::length)
        .max()
        .orElse(START_MARKER.length()) - 1;

    private final ObjectMapper mapper;

    public MemoryDecisionParser(ObjectMapper mapper) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    public Parsed parseAndStrip(String content) {
        if (content == null || content.isBlank()) {
            return new Parsed(content == null ? "" : content, MemoryDecision.FALLBACK);
        }
        String json = lastDecisionJson(content);
        if (json == null) {
            int danglingStart = firstStartMarkerIndex(content);
            if (danglingStart >= 0) {
                return new Parsed(content.substring(0, danglingStart).trim(), MemoryDecision.FALLBACK);
            }
            return new Parsed(content, MemoryDecision.FALLBACK);
        }
        return new Parsed(stripHiddenDecisionBlocks(content), parseDecision(json));
    }

    public static String stripHiddenDecisionBlocks(String content) {
        if (content == null || content.isBlank()) {
            return content == null ? null : content;
        }
        Matcher matcher = DECISION_BLOCK.matcher(content);
        String stripped = matcher.replaceAll("").trim();
        int danglingStart = firstStartMarkerIndex(stripped);
        return danglingStart >= 0 ? stripped.substring(0, danglingStart).trim() : stripped;
    }

    /**
     * 流式输出时保留尾部缓冲，避免内部 marker 被逐 token 推给前端。
     *
     * <p>返回 {@code null} 表示当前片段暂不输出。</p>
     */
    public StreamFilter streamFilter() {
        return new StreamFilter();
    }

    public String appendInstruction(String systemPrompt) {
        String base = systemPrompt == null ? "" : systemPrompt;
        return base + """

            额外内部要求：正常回答用户后，在最后追加一段隐藏记忆决策，格式必须严格如下：
            <ECHOMIND_MEMORY_DECISION>{"rememberFacts":false,"refreshProfile":false,"reason":""}</ECHOMIND_MEMORY_DECISION>
            rememberFacts=true 表示本轮对话包含值得长期保存的用户事实、偏好、稳定背景或明确否定旧事实。
            refreshProfile=true 表示本轮对话会影响用户长期画像摘要。
            一次性任务、临时链接、普通闲聊、工具查询过程通常为 false。
            JSON 字段 rememberFacts 和 refreshProfile 必须是 boolean，不要写成字符串。
            这段决策只给系统解析，不要在正文中解释它。
            """;
    }

    private MemoryDecision parseDecision(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode rememberFacts = root.path("rememberFacts");
            JsonNode refreshProfile = root.path("refreshProfile");
            if (!rememberFacts.isBoolean() || !refreshProfile.isBoolean()) {
                return MemoryDecision.FALLBACK;
            }
            return new MemoryDecision(
                rememberFacts.asBoolean(),
                refreshProfile.asBoolean(),
                true,
                text(root.path("reason"))
            );
        } catch (Exception ignored) {
            return MemoryDecision.FALLBACK;
        }
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }

    public record Parsed(String content, MemoryDecision decision) {
    }

    public final class StreamFilter {
        private final StringBuilder raw = new StringBuilder();
        private final StringBuilder buffer = new StringBuilder();
        private boolean hiddenStarted;

        public String accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return null;
            }
            raw.append(chunk);
            if (hiddenStarted) {
                buffer.append(chunk);
                return null;
            }
            buffer.append(chunk);
            int markerStart = firstStartMarkerIndex(buffer);
            if (markerStart >= 0) {
                hiddenStarted = true;
                String visible = buffer.substring(0, markerStart);
                buffer.delete(0, buffer.length());
                return visible.isBlank() ? null : visible;
            }
            int keep = MARKER_TAIL_KEEP;
            if (buffer.length() <= keep) {
                return null;
            }
            String visible = buffer.substring(0, buffer.length() - keep);
            buffer.delete(0, buffer.length() - keep);
            return visible;
        }

        public String finish() {
            if (hiddenStarted) {
                return null;
            }
            String remaining = buffer.toString();
            buffer.delete(0, buffer.length());
            return remaining.isEmpty() ? null : remaining;
        }

        public MemoryDecision decision() {
            return parseAndStrip(raw.toString()).decision();
        }
    }

    private static String lastDecisionJson(String content) {
        Matcher matcher = DECISION_BLOCK.matcher(content);
        String json = null;
        while (matcher.find()) {
            json = matcher.group(1).trim();
        }
        return json;
    }

    private static int firstStartMarkerIndex(CharSequence content) {
        if (content == null || content.length() == 0) {
            return -1;
        }
        String upper = content.toString().toUpperCase(Locale.ROOT);
        int first = -1;
        for (String marker : START_MARKERS) {
            int index = upper.indexOf(marker);
            if (index >= 0 && (first < 0 || index < first)) {
                first = index;
            }
        }
        return first;
    }
}