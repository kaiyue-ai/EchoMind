package com.echomind.agent.pipeline;

import com.echomind.common.model.MemorySignal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 解析主模型附带的隐藏记忆信号。
 *
 * <p>信号只用于触发后台长期记忆合并，解析失败时会被忽略，不能影响用户回复。</p>
 */
public class MemorySignalParser {

    public static final String START_MARKER = "<ECHOMIND_MEMORY_SIGNAL>";
    public static final String END_MARKER = "</ECHOMIND_MEMORY_SIGNAL>";

    private final ObjectMapper mapper;

    public MemorySignalParser(ObjectMapper mapper) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    public Parsed parseAndStrip(String content) {
        if (content == null || content.isBlank()) {
            return new Parsed(content == null ? "" : content, MemorySignal.NONE);
        }
        int start = content.lastIndexOf(START_MARKER);
        int end = content.lastIndexOf(END_MARKER);
        if (start < 0 || end <= start) {
            return new Parsed(content, MemorySignal.NONE);
        }
        String json = content.substring(start + START_MARKER.length(), end).trim();
        String stripped = (content.substring(0, start) + content.substring(end + END_MARKER.length())).trim();
        return new Parsed(stripped, parseSignal(json));
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

            额外内部要求：正常回答用户后，在最后追加一段隐藏记忆信号，格式必须严格如下：
            <ECHOMIND_MEMORY_SIGNAL>{"important":false,"confidence":0.0,"reason":""}</ECHOMIND_MEMORY_SIGNAL>
            当用户表达稳定偏好、长期背景、项目约束或明确否定旧偏好时 important=true；一次性任务、临时链接和普通闲聊为 false。
            这段信号只给系统解析，不要在正文中解释它。
            """;
    }

    private MemorySignal parseSignal(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            return new MemorySignal(
                root.path("important").asBoolean(false),
                root.path("confidence").asDouble(0),
                text(root.path("reason"))
            );
        } catch (Exception ignored) {
            return MemorySignal.NONE;
        }
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }

    public record Parsed(String content, MemorySignal signal) {
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
            int markerStart = buffer.indexOf(START_MARKER);
            if (markerStart >= 0) {
                hiddenStarted = true;
                String visible = buffer.substring(0, markerStart);
                buffer.delete(0, buffer.length());
                return visible.isBlank() ? null : visible;
            }
            int keep = START_MARKER.length() - 1;
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

        public MemorySignal signal() {
            return parseAndStrip(raw.toString()).signal();
        }
    }
}
