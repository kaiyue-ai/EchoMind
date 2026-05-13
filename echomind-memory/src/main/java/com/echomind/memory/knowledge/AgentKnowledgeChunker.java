package com.echomind.memory.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文本切片器。
 *
 * <p>当前采用固定窗口加重叠的保守策略：实现简单、稳定，适合先把 RAG 链路跑通。
 * 后续如果要更精细，可以在这里替换为按标题、段落或语义边界切片。</p>
 */
public class AgentKnowledgeChunker {

    private final int chunkSize;
    private final int overlap;

    public AgentKnowledgeChunker(int chunkSize, int overlap) {
        this.chunkSize = Math.max(200, chunkSize);
        this.overlap = Math.max(0, Math.min(overlap, this.chunkSize / 2));
    }

    /** 把长文本切成可向量化的片段。 */
    public List<String> split(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + chunkSize);
            chunks.add(normalized.substring(start, end).trim());
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks.stream().filter(chunk -> !chunk.isBlank()).toList();
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
