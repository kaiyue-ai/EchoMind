package com.echomind.memory.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 知识库文本切片器。
 *
 * <p>先按段落切分，超长段落再按标点切分，最后才硬切。
 * Token 预算先用字符数近似，避免引入模型特定 tokenizer。</p>
 */
public class AgentKnowledgeChunker {

    private static final Pattern PARAGRAPH_SPLITTER = Pattern.compile("\\n\\s*\\n+");

    private final int chunkSize;
    private final int overlapChars;

    public AgentKnowledgeChunker(int chunkSize, double overlapRatio) {
        this.chunkSize = Math.max(1, chunkSize);
        double safeRatio = Math.max(0, Math.min(overlapRatio, 0.5));
        this.overlapChars = (int) Math.round(this.chunkSize * safeRatio);
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
            if (clean.isBlank()) {
                continue;
            }
            rawChunks.addAll(splitParagraph(clean));
        }
        return applyOverlap(rawChunks);
    }

    private List<String> splitParagraph(String paragraph) {
        if (paragraph.length() <= chunkSize) {
            return List.of(paragraph);
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < paragraph.length(); i++) {
            char ch = paragraph.charAt(i);
            current.append(ch);
            if (isPunctuationBoundary(ch)) {
                addSentenceChunk(chunks, current.toString());
                current.setLength(0);
            }
        }
        addSentenceChunk(chunks, current.toString());
        return chunks;
    }

    private void addSentenceChunk(List<String> chunks, String value) {
        String sentence = value.trim();
        if (sentence.isBlank()) {
            return;
        }
        if (sentence.length() <= chunkSize) {
            chunks.add(sentence);
            return;
        }
        int start = 0;
        while (start < sentence.length()) {
            int end = Math.min(sentence.length(), start + chunkSize);
            chunks.add(sentence.substring(start, end).trim());
            start = end;
        }
    }

    private List<String> applyOverlap(List<String> rawChunks) {
        List<String> chunks = new ArrayList<>();
        String previous = "";
        for (String raw : rawChunks) {
            String chunk = raw.trim();
            if (chunk.isBlank()) {
                continue;
            }
            if (!previous.isBlank() && overlapChars > 0) {
                String overlap = tail(previous, Math.min(overlapChars, Math.max(0, chunkSize - chunk.length() - 2)));
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
        return chunks;
    }

    private String tail(String value, int maxChars) {
        if (maxChars <= 0 || value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(value.length() - maxChars).trim();
    }

    private boolean isPunctuationBoundary(char ch) {
        return "。！？；.!?;，,、：:".indexOf(ch) >= 0;
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
