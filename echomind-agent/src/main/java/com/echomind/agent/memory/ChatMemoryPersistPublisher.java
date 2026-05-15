package com.echomind.agent.memory;

import com.echomind.common.model.AgentMessage;

import java.util.List;

/** 发布普通聊天记忆异步持久化事件。 */
public interface ChatMemoryPersistPublisher {

    void publish(String sessionId, String agentId, List<AgentMessage> messages);
}
