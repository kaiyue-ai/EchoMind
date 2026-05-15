package com.echomind.memory.usermemory;

/** Redis Stack 中保存的一条用户长期画像。 */
public record UserMemoryEntry(
    String sessionId,
    String entryId,
    UserMemoryCategory category,
    String content,
    String evidence,
    double confidence,
    double[] embedding
) {}
