package com.echomind.console.config;

import com.echomind.common.messaging.ChatMemoryShardSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String QUEUE_CHAT_REQUESTS = "echomind.chat.requests";
    public static final String QUEUE_CHAT_RESPONSES = "echomind.chat.responses";
    public static final String QUEUE_CHAT_MEMORY_PERSIST_REQUESTS = "echomind.chat-memory.persist.requests";
    public static final String CHAT_MEMORY_PERSIST_QUEUE_PROPERTY =
        "${echomind.memory.persist-queue-name:" + QUEUE_CHAT_MEMORY_PERSIST_REQUESTS + "}";
    public static final String CHAT_MEMORY_PERSIST_SHARDS_PROPERTY = "${echomind.memory.persist-shards:8}";
    public static final String CHAT_REQUEST_LISTENER_FACTORY = "chatRequestRabbitListenerContainerFactory";
    public static final String CHAT_RESPONSE_LISTENER_FACTORY = "chatResponseRabbitListenerContainerFactory";
    public static final String CHAT_MEMORY_PERSIST_LISTENER_FACTORY = "chatMemoryPersistRabbitListenerContainerFactory";
    public static final String CHAT_MEMORY_PERSIST_QUEUE_NAMES_BEAN = "chatMemoryPersistQueueNames";

    @Bean
    public Queue chatRequestsQueue() {
        return new Queue(QUEUE_CHAT_REQUESTS, true);
    }

    @Bean
    public Queue chatResponsesQueue() {
        return new Queue(QUEUE_CHAT_RESPONSES, true);
    }

    @Bean(name = CHAT_MEMORY_PERSIST_QUEUE_NAMES_BEAN)
    public String[] chatMemoryPersistQueueNames(@Value(CHAT_MEMORY_PERSIST_QUEUE_PROPERTY) String queueName,
                                                @Value(CHAT_MEMORY_PERSIST_SHARDS_PROPERTY) int shardCount) {
        return ChatMemoryShardSupport.queueNames(queueName, shardCount).toArray(String[]::new);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter(ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean(name = CHAT_REQUEST_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory chatRequestRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            @Value("${echomind.rabbitmq.chat-request.concurrent-consumers:20}") int concurrentConsumers,
            @Value("${echomind.rabbitmq.chat-request.max-concurrent-consumers:20}") int maxConcurrentConsumers,
            @Value("${echomind.rabbitmq.chat-request.prefetch:1}") int prefetchCount) {
        return listenerFactory(connectionFactory, converter, concurrentConsumers, maxConcurrentConsumers, prefetchCount);
    }

    @Bean(name = CHAT_RESPONSE_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory chatResponseRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            @Value("${echomind.rabbitmq.chat-response.concurrent-consumers:4}") int concurrentConsumers,
            @Value("${echomind.rabbitmq.chat-response.max-concurrent-consumers:4}") int maxConcurrentConsumers,
            @Value("${echomind.rabbitmq.chat-response.prefetch:10}") int prefetchCount) {
        return listenerFactory(connectionFactory, converter, concurrentConsumers, maxConcurrentConsumers, prefetchCount);
    }

    @Bean(name = CHAT_MEMORY_PERSIST_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory chatMemoryPersistRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        // 每个分片队列只能有 1 个 consumer；整体并发靠分片数扩展，保证同一 sessionId 不乱序。
        return listenerFactory(connectionFactory, converter, 1, 1, 1);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }

    private SimpleRabbitListenerContainerFactory listenerFactory(ConnectionFactory connectionFactory,
                                                                 Jackson2JsonMessageConverter converter,
                                                                 int concurrentConsumers,
                                                                 int maxConcurrentConsumers,
                                                                 int prefetchCount) {
        int consumers = Math.max(1, concurrentConsumers);
        int maxConsumers = Math.max(consumers, maxConcurrentConsumers);
        int prefetch = Math.max(1, prefetchCount);

        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setConcurrentConsumers(consumers);
        factory.setMaxConcurrentConsumers(maxConsumers);
        factory.setPrefetchCount(prefetch);
        return factory;
    }
}
