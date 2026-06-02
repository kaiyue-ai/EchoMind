package com.echomind.memory.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 知识库文本切片器。
 *
 * <p>优先按自然段切片；自然段超限时再按标点切片；标点单元仍超限时才硬切。
 * 每个切片会尽量带上约 15% 的前文重叠，但最终仍不超过单片预算。</p>
 */
public class AgentKnowledgeChunker {

    private static final Pattern PARAGRAPH_SPLITTER = Pattern.compile("\\n\\s*\\n+");
    private static final String PUNCTUATION = "。！？；.!?;，,、：:";

    private final int chunkSize;
    private final int overlapChars;
    private final int bodyChunkSize;

    public AgentKnowledgeChunker(int chunkSize, double overlapRatio) {
        this.chunkSize = Math.max(1, chunkSize);
        double safeRatio = Math.max(0, Math.min(overlapRatio, 0.5));
        this.overlapChars = (int) Math.round(this.chunkSize * safeRatio);
        int separatorBudget = this.overlapChars > 0 ? 2 : 0;
        this.bodyChunkSize = Math.max(1, this.chunkSize - this.overlapChars - separatorBudget);
    }

    /** 把长文本切成可向量化的片段。 */
    public List<String> split(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> rawChunks = new ArrayList<>();
        for (String paragraph : PARAGRAPH_SPLITTER.split(normalized)) {
            String clean = paragraph.trim();
            if (!clean.isBlank()) {
                rawChunks.addAll(splitParagraph(clean));
            }
        }
        return applyOverlap(rawChunks);
    }

    private List<String> splitParagraph(String paragraph) {
        if (paragraph.length() <= chunkSize) {
            return List.of(paragraph);
        }
        List<String> chunks = new ArrayList<>();
        List<String> units = splitByPunctuation(paragraph);
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (unit.length() > chunkSize) {
                addSizedChunks(chunks, current.toString());
                current.setLength(0);
                addSizedChunks(chunks, unit);
                continue;
            }
            if (!current.isEmpty() && current.length() + unit.length() > chunkSize) {
                addSizedChunks(chunks, current.toString());
                current.setLength(0);
            }
            current.append(unit);
        }
        if (!current.isEmpty()) {
            addSizedChunks(chunks, current.toString());
        }
        return chunks;
    }

    private List<String> splitByPunctuation(String paragraph) {
        List<String> units = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < paragraph.length(); i++) {
            char ch = paragraph.charAt(i);
            current.append(ch);
            if (isBoundary(ch)) {
                units.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            units.add(current.toString());
        }
        return units.isEmpty() ? List.of(paragraph) : units;
    }

    private void addSizedChunks(List<String> chunks, String value) {
        String clean = value.trim();
        if (clean.isBlank()) {
            return;
        }
        if (clean.length() <= chunkSize) {
            chunks.add(clean);
            return;
        }
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(clean.length(), start + chunkSize);
            String chunk = clean.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end >= clean.length()) {
                break;
            }
            start = end;
        }
    }

    private List<String> applyOverlap(List<String> rawChunks) {
        List<String> chunks = new ArrayList<>();
        String previous = "";
        for (String raw : rawChunks) {
            for (String piece : splitForOverlapBudget(raw, previous.isBlank())) {
                String chunk = piece.trim();
                if (chunk.isBlank()) {
                    continue;
                }
                if (!previous.isBlank() && overlapChars > 0) {
                    String overlap = tail(previous, overlapChars);
                    if (!overlap.isBlank()) {
                        chunk = overlap + "\n\n" + chunk;
                    }
                }
                if (chunk.length() > chunkSize) {
                    chunk = chunk.substring(chunk.length() - chunkSize).trim();
                }
                chunks.add(chunk);
                previous = chunk;
            }
        }
        return chunks;
    }

    private List<String> splitForOverlapBudget(String raw, boolean firstChunk) {
        String clean = raw == null ? "" : raw.trim();
        if (clean.isBlank()) {
            return List.of();
        }
        int budget = firstChunk || overlapChars <= 0 ? chunkSize : bodyChunkSize;
        if (clean.length() <= budget) {
            return List.of(clean);
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(clean.length(), start + budget);
            String piece = clean.substring(start, end).trim();
            if (!piece.isBlank()) {
                pieces.add(piece);
            }
            if (end >= clean.length()) {
                break;
            }
            start = end;
        }
        return pieces;
    }

    private String tail(String value, int maxChars) {
        if (maxChars <= 0 || value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(value.length() - maxChars);
    }

    private boolean isBoundary(char ch) {
        return PUNCTUATION.indexOf(ch) >= 0;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("[\\t\\x0B\\f]+", " ")
            .replaceAll(" {2,}", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }
}
