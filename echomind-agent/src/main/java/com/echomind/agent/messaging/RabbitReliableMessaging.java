package com.echomind.agent.messaging;

import com.echomind.common.observability.EchoMindTrace;
import com.echomind.common.messaging.RabbitQueueNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.UUID;

/** Shared RabbitMQ reliability helpers for persistent publishing and callbacks. */
@Slf4j
public final class RabbitReliableMessaging {

    public static final String DEAD_LETTER_EXCHANGE = RabbitQueueNames.DEAD_LETTER_EXCHANGE;
    public static final String CHAT_REQUESTS_DLQ = RabbitQueueNames.CHAT_REQUESTS_DLQ;
    public static final String CHAT_MEMORY_PERSIST_DLQ = RabbitQueueNames.CHAT_MEMORY_PERSIST_DLQ;
    public static final String USER_MEMORY_DLQ = RabbitQueueNames.USER_MEMORY_DLQ;
    public static final String TEAM_COMMANDS_DLQ = RabbitQueueNames.TEAM_COMMANDS_DLQ;

    private static final MessagePostProcessor PERSISTENT_MESSAGE = message -> {
        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return message;
    };

    private RabbitReliableMessaging() {
    }

    public static MessagePostProcessor persistentMessage() {
        return PERSISTENT_MESSAGE;
    }

    public static CorrelationData correlation(String messageType, String businessId) {
        String safeType = messageType == null || messageType.isBlank() ? "message" : messageType;
        String safeBusinessId = businessId == null || businessId.isBlank() ? UUID.randomUUID().toString() : businessId;
        return new CorrelationData(safeType + ":" + safeBusinessId + ":" + UUID.randomUUID());
    }

    public static void configureTemplate(RabbitTemplate template, String owner) {
        template.setMandatory(true);
        template.setConfirmCallback((correlation, ack, cause) -> {
            if (ack) {
                return;
            }
            String id = correlation == null ? "" : correlation.getId();
            RuntimeException failure = new RabbitPublishException("RabbitMQ publisher confirm failed owner="
                + owner + " correlationId=" + id + " cause=" + safe(cause));
            EchoMindTrace.recordException(EchoMindTrace.currentSpan(), failure);
            log.warn("RabbitMQ publisher confirm failed owner={} correlationId={} cause={}", owner, id, safe(cause));
        });
        template.setReturnsCallback(returned -> {
            Message message = returned.getMessage();
            String messageId = message == null ? "" : message.getMessageProperties().getMessageId();
            RuntimeException failure = new RabbitPublishException("RabbitMQ message returned owner="
                + owner + " exchange=" + returned.getExchange() + " routingKey=" + returned.getRoutingKey()
                + " replyCode=" + returned.getReplyCode() + " replyText=" + returned.getReplyText());
            EchoMindTrace.recordException(EchoMindTrace.currentSpan(), failure);
            log.warn("RabbitMQ message returned owner={} exchange={} routingKey={} replyCode={} replyText={} messageId={}",
                owner, returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(),
                returned.getReplyText(), messageId);
        });
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class RabbitPublishException extends RuntimeException {
        private RabbitPublishException(String message) {
            super(message);
        }
    }
}
