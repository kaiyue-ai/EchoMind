package com.echomind.memory.usermemory;

import java.time.Instant;

/** Redis 中保存的用户画像快照，用于 prompt 常驻背景。 */
public record UserProfileSnapshot(
    String userId,
    String content,
    int version,
    Instant updatedAt
) {
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}
