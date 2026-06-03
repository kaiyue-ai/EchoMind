package com.echomind.agent.memory;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MemoryDecision;

import java.util.List;

/** 未配置队列时的空实现。 */
public class NoopChatMemoryPersistPublisher implements ChatMemoryPersistPublisher {

    @Override
    public void publish(String sessionId, String agentId, List<AgentMessage> messages) {
        // no-op
    }

    @Override
    public void publish(String userId, String sessionId, String agentId, List<AgentMessage> messages) {
        // no-op
    }

    @Override
    public void publish(String userId, String sessionId, String agentId,
                        List<AgentMessage> messages, MemoryDecision memoryDecision) {
        // no-op
    }
}
