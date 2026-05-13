package com.echomind.memory.embedding;

/** 向量检索命中的记忆片段。 */
public record MemorySearchHit(
    Long messageId,
    String role,
    String content,
    double score
) {}
