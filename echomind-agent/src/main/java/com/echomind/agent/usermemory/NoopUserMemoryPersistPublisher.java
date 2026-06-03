package com.echomind.agent.usermemory;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MemoryDecision;

import java.util.List;

/** 用户长期画像关闭时使用的空实现。 */
public class NoopUserMemoryPersistPublisher implements UserMemoryPersistPublisher {

    @Override
    public void publish(String sessionId, String agentId, List<AgentMessage> messages) {
    }

    @Override
    public void publish(String userId, String sessionId, String agentId, List<AgentMessage> messages) {
    }

    @Override
    public void publish(String userId, String sessionId, String agentId,
                        List<AgentMessage> messages, MemoryDecision memoryDecision) {
    }
}
