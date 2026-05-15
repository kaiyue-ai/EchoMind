package com.echomind.agent.memory;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.ChatMemoryPersistEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

/** RabbitMQ 普通聊天记忆持久化事件发布器。 */
@Slf4j
public class RabbitChatMemoryPersistPublisher implements ChatMemoryPersistPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String queueName;
    private final boolean enabled;

    public RabbitChatMemoryPersistPublisher(RabbitTemplate rabbitTemplate, String queueName, boolean enabled) {
        this.rabbitTemplate = rabbitTemplate;
        this.queueName = queueName;
        this.enabled = enabled;
    }

    @Override
    public void publish(String sessionId, String agentId, List<AgentMessage> messages) {
        if (!enabled || rabbitTemplate == null || queueName == null || queueName.isBlank()
            || sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            rabbitTemplate.convertAndSend(queueName, new ChatMemoryPersistEvent(sessionId, agentId, messages));
        } catch (Exception e) {
            log.warn("Failed to publish chat memory persist event sessionId={}: {}", sessionId, e.getMessage());
        }
    }
}
