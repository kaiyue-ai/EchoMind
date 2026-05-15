package com.echomind.common.model;

import java.util.List;

/** 用户长期画像异步提取事件。 */
public record UserMemoryEvent(
    String sessionId,
    String agentId,
    List<AgentMessage> messages
) {}
