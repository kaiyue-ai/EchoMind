package com.echomind.common.model;

import java.util.List;

/** 普通聊天记忆异步持久化事件。 */
public record ChatMemoryPersistEvent(
    String userId,
    String sessionId,
    String agentId,
    List<AgentMessage> messages
) {
    /** 兼容旧事件，消费端会把 userId 归到 default。 */
    public ChatMemoryPersistEvent(String sessionId, String agentId, List<AgentMessage> messages) {
        this(null, sessionId, agentId, messages);
    }
}
