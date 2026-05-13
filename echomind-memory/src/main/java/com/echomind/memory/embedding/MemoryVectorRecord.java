package com.echomind.memory.embedding;

/** 一条可写入向量索引的会话记忆。 */
public record MemoryVectorRecord(
    String sessionId,
    Long messageId,
    String role,
    String contentPreview,
    double[] embedding
) {}
