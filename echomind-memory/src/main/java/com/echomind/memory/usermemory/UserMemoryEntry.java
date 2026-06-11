package com.echomind.memory.usermemory;

import java.time.Instant;

/** Milvus 中保存的一条会话事件事实。 */
public record UserMemoryEntry(
    String sessionId,
    String entryId,
    UserMemoryCategory category,
    String content,
    String evidence,
    double confidence,
    Instant firstObservedAt,
    Instant lastObservedAt,
    Instant updatedAt
) {
    public UserMemoryEntry {
        Instant now = Instant.now();
        firstObservedAt = firstObservedAt == null ? now : firstObservedAt;
        lastObservedAt = lastObservedAt == null ? firstObservedAt : lastObservedAt;
        updatedAt = updatedAt == null ? now : updatedAt;
    }
}
