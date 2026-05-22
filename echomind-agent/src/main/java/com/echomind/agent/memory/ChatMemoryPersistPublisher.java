package com.echomind.agent.memory;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MemorySignal;

import java.util.List;

/** 发布普通聊天记忆异步持久化事件。 */
public interface ChatMemoryPersistPublisher {

    void publish(String sessionId, String agentId, List<AgentMessage> messages);

    default void publish(String userId, String sessionId, String agentId, List<AgentMessage> messages) {
        publish(sessionId, agentId, messages);
    }

    default void publish(String userId, String sessionId, String agentId,
                         List<AgentMessage> messages, MemorySignal memorySignal) {
        publish(userId, sessionId, agentId, messages);
    }
}
