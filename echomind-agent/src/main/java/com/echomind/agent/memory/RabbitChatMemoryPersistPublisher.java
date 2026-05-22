package com.echomind.agent.memory;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.ChatMemoryPersistEvent;
import com.echomind.common.model.MemorySignal;
import com.echomind.common.messaging.ChatMemoryShardSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

/** RabbitMQ 普通聊天记忆持久化事件发布器。 */
@Slf4j
public class RabbitChatMemoryPersistPublisher implements ChatMemoryPersistPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchangeName;
    private final int shardCount;
    private final boolean enabled;

    public RabbitChatMemoryPersistPublisher(RabbitTemplate rabbitTemplate, String exchangeName,
                                            int shardCount, boolean enabled) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchangeName = exchangeName;
        this.shardCount = ChatMemoryShardSupport.normalizeShardCount(shardCount);
        this.enabled = enabled;
    }

    @Override
    public void publish(String sessionId, String agentId, List<AgentMessage> messages) {
        publish(null, sessionId, agentId, messages);
    }

    @Override
    public void publish(String userId, String sessionId, String agentId, List<AgentMessage> messages) {
        publish(userId, sessionId, agentId, messages, MemorySignal.NONE);
    }

    @Override
    public void publish(String userId, String sessionId, String agentId,
                        List<AgentMessage> messages, MemorySignal memorySignal) {
        if (!enabled || rabbitTemplate == null || exchangeName == null || exchangeName.isBlank()
            || sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            int shardIndex = ChatMemoryShardSupport.shardIndex(sessionId, shardCount);
            String routingKey = ChatMemoryShardSupport.routingKey(shardIndex);
            rabbitTemplate.convertAndSend(exchangeName, routingKey,
                new ChatMemoryPersistEvent(userId, sessionId, agentId, messages, memorySignal));
        } catch (Exception e) {
            log.warn("Failed to publish chat memory persist event sessionId={}: {}", sessionId, e.getMessage());
        }
    }
}
