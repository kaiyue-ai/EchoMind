package com.echomind.usermemory.service;

import com.echomind.common.model.AgentMessage;

import java.time.Instant;
import java.util.List;

/** 用户长期记忆缓冲区中的一轮对话。 */
public record UserMemoryBatchTurn(
    String userId,
    String sessionId,
    String agentId,
    Instant createdAt,
    List<AgentMessage> messages
) {
}
