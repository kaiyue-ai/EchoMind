package com.echomind.common.model;

import java.util.List;

/** 普通聊天记忆异步持久化事件。 */
public record ChatMemoryPersistEvent(
    String sessionId,
    String agentId,
    List<AgentMessage> messages
) {}
