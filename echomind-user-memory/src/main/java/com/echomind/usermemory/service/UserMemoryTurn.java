package com.echomind.usermemory.service;

import com.echomind.common.model.AgentMessage;

import java.time.Instant;
import java.util.List;

/** 用户长期记忆异步处理的一轮对话。 */
public record UserMemoryTurn(
    String userId,
    String sessionId,
    String agentId,
    Instant createdAt,
    List<AgentMessage> messages
) {
    public UserMemoryTurn {
        createdAt = createdAt == null ? Instant.now() : createdAt;
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
