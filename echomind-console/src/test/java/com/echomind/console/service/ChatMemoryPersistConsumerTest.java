package com.echomind.console.service;

import com.echomind.agent.usermemory.UserMemoryPersistPublisher;
import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.ChatMemoryPersistEvent;
import com.echomind.common.model.MemorySignal;
import com.echomind.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChatMemoryPersistConsumerTest {

    @Test
    void persistsMessagesInOrderThenPublishesUserMemoryEvent() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        UserMemoryPersistPublisher publisher = mock(UserMemoryPersistPublisher.class);
        ChatMemoryPersistConsumer consumer = new ChatMemoryPersistConsumer(memoryManager, publisher);
        List<AgentMessage> messages = List.of(
            AgentMessage.user("你好"),
            AgentMessage.assistant("你好呀")
        );

        consumer.onPersistEvent(new ChatMemoryPersistEvent("session-1", "default", messages));

        var ordered = inOrder(memoryManager, publisher);
        ordered.verify(memoryManager).addMessage("default", "session-1", "default", messages.get(0));
        ordered.verify(memoryManager).addMessage("default", "session-1", "default", messages.get(1));
        ordered.verify(publisher).publish("default", "session-1", "default", messages, MemorySignal.NONE);
    }

    @Test
    void ignoresInvalidEvent() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        UserMemoryPersistPublisher publisher = mock(UserMemoryPersistPublisher.class);
        ChatMemoryPersistConsumer consumer = new ChatMemoryPersistConsumer(memoryManager, publisher);

        consumer.onPersistEvent(new ChatMemoryPersistEvent(" ", "default", List.of(AgentMessage.user("x"))));

        verify(memoryManager, never()).addMessage(any(), any(), any(), any());
        verify(publisher, never()).publish(any(), any(), any(), any());
        verify(publisher, never()).publish(any(), any(), any(), any(), any());
    }
}
