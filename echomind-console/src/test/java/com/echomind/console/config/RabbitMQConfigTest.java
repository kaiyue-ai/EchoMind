package com.echomind.console.config;

import com.echomind.agent.messaging.RabbitReliableMessaging;
import com.echomind.console.deadletter.RabbitDeadLetterConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

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
    void declaresCoreDeadLetterQueuesBoundToDlx() {
        RabbitMQConfig config = new RabbitMQConfig();

        Declarables declarables = config.consoleDeadLetterQueues(config.deadLetterExchange());
        List<Queue> queues = declarables.getDeclarablesByType(Queue.class);
        List<Binding> bindings = declarables.getDeclarablesByType(Binding.class);

        assertThat(queues).extracting(Queue::getName)
            .containsExactlyInAnyOrder(
                RabbitReliableMessaging.CHAT_REQUESTS_DLQ,
                RabbitReliableMessaging.CHAT_MEMORY_PERSIST_DLQ,
                RabbitReliableMessaging.USER_MEMORY_DLQ
            );
        assertThat(bindings).extracting(Binding::getDestination)
            .containsExactlyInAnyOrder(
                RabbitReliableMessaging.CHAT_REQUESTS_DLQ,
                RabbitReliableMessaging.CHAT_MEMORY_PERSIST_DLQ,
                RabbitReliableMessaging.USER_MEMORY_DLQ
            );
        assertThat(bindings).extracting(Binding::getExchange)
            .containsOnly(RabbitReliableMessaging.DEAD_LETTER_EXCHANGE);
    }

    @Test
    void deadLetterListenerUsesManualAck() {
        RabbitMQConfig config = new RabbitMQConfig();

        var factory = config.deadLetterRabbitListenerContainerFactory(
            mock(ConnectionFactory.class),
            mock(Jackson2JsonMessageConverter.class),
            1
        );

        assertThat(ReflectionTestUtils.getField(factory, "acknowledgeMode"))
            .isEqualTo(AcknowledgeMode.MANUAL);
    }

    @Test
    void deadLetterConsumerListensToAllReliableDlqs() throws Exception {
        Method method = RabbitDeadLetterConsumer.class.getMethod("onDeadLetter",
            org.springframework.amqp.core.Message.class,
            com.rabbitmq.client.Channel.class);
        RabbitListener listener = method.getAnnotation(RabbitListener.class);

        assertThat(listener.containerFactory()).isEqualTo(RabbitMQConfig.DEAD_LETTER_LISTENER_FACTORY);
        assertThat(Arrays.asList(listener.queues()))
            .containsExactlyInAnyOrder(
                RabbitReliableMessaging.CHAT_REQUESTS_DLQ,
                RabbitReliableMessaging.CHAT_MEMORY_PERSIST_DLQ,
                RabbitReliableMessaging.USER_MEMORY_DLQ
            );
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
