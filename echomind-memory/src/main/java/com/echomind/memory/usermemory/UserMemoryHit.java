package com.echomind.memory.usermemory;

import java.time.Instant;

/** 用户画像召回命中。 */
public record UserMemoryHit(
    String entryId,
    UserMemoryCategory category,
    String content,
    String evidence,
    double confidence,
    Instant firstObservedAt,
    Instant lastObservedAt,
    Instant updatedAt,
    double score
) {
    public UserMemoryHit(String entryId,
                         UserMemoryCategory category,
                         String content,
                         String evidence,
                         double confidence,
                         double score) {
        this(entryId, category, content, evidence, confidence, null, null, null, score);
    }
}
