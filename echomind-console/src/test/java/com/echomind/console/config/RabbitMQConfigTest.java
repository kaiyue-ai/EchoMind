package com.echomind.console.config;

import com.echomind.agent.messaging.RabbitReliableMessaging;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQConfigTest {

    @Test
    void buildsChatMemoryPersistShardQueueNames() {
        RabbitMQConfig config = new RabbitMQConfig();

        assertThat(config.chatMemoryPersistQueueNames("chat.memory.persist", 4))
            .containsExactly(
                "chat.memory.persist.shard.0",
                "chat.memory.persist.shard.1",
                "chat.memory.persist.shard.2",
                "chat.memory.persist.shard.3"
            );
    }

    @Test
    void normalizesInvalidShardCountToOneQueue() {
        RabbitMQConfig config = new RabbitMQConfig();

        assertThat(config.chatMemoryPersistQueueNames("chat.memory.persist", 0))
            .containsExactly("chat.memory.persist.shard.0");
    }

    @Test
    void chatRequestQueueIsDurableAndDeadLettered() {
        RabbitMQConfig config = new RabbitMQConfig();

        Queue queue = config.chatRequestsQueue();

        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
            .containsEntry("x-dead-letter-exchange", RabbitReliableMessaging.DEAD_LETTER_EXCHANGE)
            .containsEntry("x-dead-letter-routing-key", RabbitReliableMessaging.CHAT_REQUESTS_DLQ);
    }

    @Test
    void rabbitTemplateUsesMandatoryPublishing() {
        RabbitMQConfig config = new RabbitMQConfig();

        RabbitTemplate template = config.rabbitTemplate(
            mock(ConnectionFactory.class),
            mock(Jackson2JsonMessageConverter.class)
        );

        assertThat(ReflectionTestUtils.getField(template, "mandatoryExpression")).isNotNull();
    }
}
