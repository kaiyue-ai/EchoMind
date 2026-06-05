package com.echomind.common.messaging;

/** Shared RabbitMQ exchange and queue names. */
public final class RabbitQueueNames {

    public static final String DEAD_LETTER_EXCHANGE = "echomind.dlx";
    public static final String CHAT_REQUESTS_DLQ = "echomind.chat.requests.dlq";
    public static final String CHAT_MEMORY_PERSIST_DLQ = "echomind.chat-memory.persist.requests.dlq";
    public static final String USER_MEMORY_DLQ = "echomind.user-memory.requests.dlq";

    private RabbitQueueNames() {
    }
}
