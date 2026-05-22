package com.echomind.common.model;

import java.util.List;

/** 用户长期画像异步提取事件。 */
public record UserMemoryEvent(
    String userId,
    String sessionId,
    String agentId,
    List<AgentMessage> messages,
    MemorySignal memorySignal
) {
    public UserMemoryEvent {
        memorySignal = memorySignal == null ? MemorySignal.NONE : memorySignal;
    }

    public UserMemoryEvent(String userId, String sessionId, String agentId, List<AgentMessage> messages) {
        this(userId, sessionId, agentId, messages, MemorySignal.NONE);
    }

    /** 兼容旧事件，消费端会把 userId 归到 default。 */
    public UserMemoryEvent(String sessionId, String agentId, List<AgentMessage> messages) {
        this(null, sessionId, agentId, messages, MemorySignal.NONE);
    }
}
