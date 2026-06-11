package com.echomind.console.deadletter;

import com.echomind.agent.messaging.RabbitReliableMessaging;
import com.echomind.console.config.RabbitMQConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@Slf4j
@RequiredArgsConstructor
public class RabbitDeadLetterConsumer {

    private final RabbitDeadLetterService deadLetterService;

    @RabbitListener(
        queues = {
            RabbitReliableMessaging.CHAT_REQUESTS_DLQ,
            RabbitReliableMessaging.CHAT_MEMORY_PERSIST_DLQ,
            RabbitReliableMessaging.USER_MEMORY_DLQ,
            "echomind.team.run-events.dlq",
            "echomind.team.step-execute.dlq"
        },
        containerFactory = RabbitMQConfig.DEAD_LETTER_LISTENER_FACTORY
    )
    public void onDeadLetter(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String queue = message.getMessageProperties().getConsumerQueue();
        try {
            deadLetterService.archiveAndCompensate(queue, message);
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException e) {
            log.warn("Failed to archive RabbitMQ DLQ message queue={}: {}", queue, e.getMessage());
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
