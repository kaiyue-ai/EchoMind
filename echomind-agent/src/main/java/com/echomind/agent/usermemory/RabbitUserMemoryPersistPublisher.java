package com.echomind.agent.usermemory;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.UserMemoryEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

/** RabbitMQ 用户长期画像事件发布器。 */
@Slf4j
public class RabbitUserMemoryPersistPublisher implements UserMemoryPersistPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String queueName;
    private final boolean enabled;

    public RabbitUserMemoryPersistPublisher(RabbitTemplate rabbitTemplate, String queueName, boolean enabled) {
        this.rabbitTemplate = rabbitTemplate;
        this.queueName = queueName;
        this.enabled = enabled;
    }

    @Override
    public void publish(String sessionId, String agentId, List<AgentMessage> messages) {
        publish(null, sessionId, agentId, messages);
    }

    @Override
    public void publish(String userId, String sessionId, String agentId, List<AgentMessage> messages) {
        if (!enabled || rabbitTemplate == null || queueName == null || queueName.isBlank()
            || sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            rabbitTemplate.convertAndSend(queueName, new UserMemoryEvent(userId, sessionId, agentId, messages));
        } catch (Exception e) {
            log.warn("Failed to publish user memory event sessionId={}: {}", sessionId, e.getMessage());
        }
    }
}
