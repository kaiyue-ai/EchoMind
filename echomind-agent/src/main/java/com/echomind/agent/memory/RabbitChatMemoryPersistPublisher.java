package com.echomind.agent.memory;

import com.echomind.agent.messaging.RabbitReliableMessaging;
import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.ChatMemoryPersistEvent;
import com.echomind.common.model.MemoryDecision;
import com.echomind.common.messaging.ChatMemoryShardSupport;
import com.echomind.common.observability.EchoMindTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

/** RabbitMQ 普通聊天记忆持久化事件发布器。 */
@Slf4j
public class RabbitChatMemoryPersistPublisher implements ChatMemoryPersistPublisher {

    private final RabbitTemplate rabbitTemplate; // RabbitMQ 模板
    private final String exchangeName; // 交换机名称
    private final int shardCount; // 分片的数量
    private final boolean enabled; // 是否启用

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
        publish(userId, sessionId, agentId, messages, MemoryDecision.FALLBACK);
    }

    @Override
    public void publish(String userId, String sessionId, String agentId,
                        List<AgentMessage> messages, MemoryDecision memoryDecision) {
        // 如果啥都没有,那也不用存储了
        if (!enabled || rabbitTemplate == null || exchangeName == null || exchangeName.isBlank()
            || sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            // 同一个sessionId一定落到一个分片上去
            int shardIndex = ChatMemoryShardSupport.shardIndex(sessionId, shardCount);
            // 创建一个routingKey
            String routingKey = ChatMemoryShardSupport.routingKey(shardIndex);
            // 发消息
            rabbitTemplate.convertAndSend(exchangeName, routingKey,
                new ChatMemoryPersistEvent(
                    userId,
                    sessionId,
                    agentId,
                    messages,
                    memoryDecision,
                    EchoMindTrace.currentTraceId(),
                    EchoMindTrace.injectContext().get("traceparent")
                ),
                RabbitReliableMessaging.persistentMessage(),
                RabbitReliableMessaging.correlation("chat-memory", sessionId));
        } catch (Exception e) {
            log.warn("Failed to publish chat memory persist event sessionId={}: {}", sessionId, e.getMessage());
        }
    }
}
