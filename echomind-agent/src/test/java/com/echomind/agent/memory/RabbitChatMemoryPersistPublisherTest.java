package com.echomind.agent.memory;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.ChatMemoryPersistEvent;
import com.echomind.common.messaging.ChatMemoryShardSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RabbitChatMemoryPersistPublisherTest {

    @Test
    void routesSameSessionToSameShard() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        RabbitChatMemoryPersistPublisher publisher =
            new RabbitChatMemoryPersistPublisher(template, "chat.memory.exchange", 8, true);

        publisher.publish("session-a", "default", List.of(AgentMessage.user("one")));
        publisher.publish("session-a", "default", List.of(AgentMessage.user("two")));

        ArgumentCaptor<String> routingKeys = ArgumentCaptor.forClass(String.class);
        verify(template, times(2)).convertAndSend(
            eq("chat.memory.exchange"),
            routingKeys.capture(),
            org.mockito.ArgumentMatchers.any(ChatMemoryPersistEvent.class),
            org.mockito.ArgumentMatchers.any(MessagePostProcessor.class),
            org.mockito.ArgumentMatchers.any(CorrelationData.class)
        );
        assertThat(routingKeys.getAllValues()).containsOnly(routingKeys.getAllValues().get(0));
    }

    @Test
    void routesDifferentSessionsByStableHash() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        RabbitChatMemoryPersistPublisher publisher =
            new RabbitChatMemoryPersistPublisher(template, "chat.memory.exchange", 8, true);

        publisher.publish("session-a", "default", List.of(AgentMessage.user("a")));
        publisher.publish("session-b", "default", List.of(AgentMessage.user("b")));

        ArgumentCaptor<String> routingKeys = ArgumentCaptor.forClass(String.class);
        verify(template, times(2)).convertAndSend(
            eq("chat.memory.exchange"),
            routingKeys.capture(),
            org.mockito.ArgumentMatchers.any(ChatMemoryPersistEvent.class),
            org.mockito.ArgumentMatchers.any(MessagePostProcessor.class),
            org.mockito.ArgumentMatchers.any(CorrelationData.class)
        );
        assertThat(routingKeys.getAllValues()).containsExactly(
            ChatMemoryShardSupport.routingKey(ChatMemoryShardSupport.shardIndex("session-a", 8)),
            ChatMemoryShardSupport.routingKey(ChatMemoryShardSupport.shardIndex("session-b", 8))
        );
    }

    @Test
    void ignoresInvalidOrDisabledPublishRequest() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        RabbitChatMemoryPersistPublisher publisher =
            new RabbitChatMemoryPersistPublisher(template, "chat.memory.exchange", 8, false);

        publisher.publish("session-a", "default", List.of(AgentMessage.user("a")));

        verifyNoInteractions(template);
    }
}
