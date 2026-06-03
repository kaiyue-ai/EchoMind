package com.echomind.agent.usermemory;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MemoryDecision;

import java.util.List;

/** 发布用户长期画像异步提取事件。 */
public interface UserMemoryPersistPublisher {

    void publish(String sessionId, String agentId, List<AgentMessage> messages);

    default void publish(String userId, String sessionId, String agentId, List<AgentMessage> messages) {
        publish(sessionId, agentId, messages);
    }

    default void publish(String userId, String sessionId, String agentId,
                         List<AgentMessage> messages, MemoryDecision memoryDecision) {
        publish(userId, sessionId, agentId, messages);
    }
}
