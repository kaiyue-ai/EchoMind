package com.echomind.common.model;

/** 触发用户长期记忆缓冲区合并的事件。 */
public record UserMemoryFlushEvent(
    String userId,
    String reason
) {
    public UserMemoryFlushEvent {
        userId = userId == null || userId.isBlank() ? "default" : userId;
        reason = reason == null ? "threshold" : reason;
    }
}
