package com.echomind.console.service;

import com.echomind.agent.usermemory.UserMemoryPersistPublisher;
import com.echomind.common.model.ChatMemoryPersistEvent;
import com.echomind.memory.MemoryManager;
import com.echomind.console.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

/** 后台消费普通聊天记忆写入事件，负责 MySQL、Redis、向量索引和用户画像事件。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatMemoryPersistConsumer {

    private final MemoryManager memoryManager;
    private final UserMemoryPersistPublisher userMemoryPublisher;

    @RabbitListener(
        queues = "#{@" + RabbitMQConfig.CHAT_MEMORY_PERSIST_QUEUE_NAMES_BEAN + "}",
        containerFactory = RabbitMQConfig.CHAT_MEMORY_PERSIST_LISTENER_FACTORY
    )
    public void onPersistEvent(ChatMemoryPersistEvent event) {
        if (event == null || event.sessionId() == null || event.sessionId().isBlank()
            || event.messages() == null || event.messages().isEmpty()) {
            return;
        }
        String userId = normalizeUserId(event.userId());
        for (var message : event.messages()) {
            memoryManager.addMessage(userId, event.sessionId(), event.agentId(), message);
        }
        userMemoryPublisher.publish(userId, event.sessionId(), event.agentId(), event.messages(), event.memorySignal());
        log.debug("Persisted chat memory event userId={} sessionId={} messages={}",
            userId, event.sessionId(), event.messages().size());
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }
}
