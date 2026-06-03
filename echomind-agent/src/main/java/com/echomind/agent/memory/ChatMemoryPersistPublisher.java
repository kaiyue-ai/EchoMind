package com.echomind.agent.memory;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MemoryDecision;

import java.util.List;

/** 发布普通聊天记忆异步持久化事件。 */
public interface ChatMemoryPersistPublisher {
    // 发布普通聊天记忆异步持久化事件
    void publish(String sessionId, String agentId, List<AgentMessage> messages);
    // 发布普通聊天记忆异步持久化事件,包含用户id
    default void publish(String userId, String sessionId, String agentId, List<AgentMessage> messages) {
        publish(sessionId, agentId, messages);
    }
    // 发布普通聊天记忆异步持久化事件,包含用户id和记忆决策
    default void publish(String userId, String sessionId, String agentId,
                         List<AgentMessage> messages, MemoryDecision memoryDecision) {
        publish(userId, sessionId, agentId, messages);
    }
}
