package com.echomind.memory.usermemory;

/** 用户画像召回命中。 */
public record UserMemoryHit(
    String entryId,
    UserMemoryCategory category,
    String content,
    String evidence,
    double confidence,
    double score
) {}
