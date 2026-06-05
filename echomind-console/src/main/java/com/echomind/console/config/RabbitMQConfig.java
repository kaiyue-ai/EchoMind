package com.echomind.console.config;

import com.echomind.common.messaging.ChatMemoryShardSupport;
import com.echomind.agent.messaging.RabbitReliableMessaging;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    public static final String QUEUE_CHAT_REQUESTS = "echomind.chat.requests";
    public static final String QUEUE_CHAT_STREAM_EVENTS = "echomind.chat.stream-events";
    public static final String QUEUE_CHAT_MEMORY_PERSIST_REQUESTS = "echomind.chat-memory.persist.requests";
    public static final String CHAT_MEMORY_PERSIST_QUEUE_PROPERTY =
        "${echomind.memory.persist-queue-name:" + QUEUE_CHAT_MEMORY_PERSIST_REQUESTS + "}";
    public static final String CHAT_MEMORY_PERSIST_SHARDS_PROPERTY = "${echomind.memory.persist-shards:8}";
    public static final String CHAT_REQUEST_LISTENER_FACTORY = "chatRequestRabbitListenerContainerFactory";
    public static final String CHAT_STREAM_EVENT_LISTENER_FACTORY = "chatStreamEventRabbitListenerContainerFactory";
    public static final String CHAT_MEMORY_PERSIST_LISTENER_FACTORY = "chatMemoryPersistRabbitListenerContainerFactory";
    public static final String CHAT_MEMORY_PERSIST_QUEUE_NAMES_BEAN = "chatMemoryPersistQueueNames";

    @Bean
    public Queue chatRequestsQueue() {
        return durableQueue(QUEUE_CHAT_REQUESTS, RabbitReliableMessaging.CHAT_REQUESTS_DLQ);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(RabbitReliableMessaging.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Declarables consoleDeadLetterQueues(DirectExchange deadLetterExchange) {
        List<Declarable> declarables = new ArrayList<>();
        addDeadLetterQueue(declarables, deadLetterExchange, RabbitReliableMessaging.CHAT_REQUESTS_DLQ);
        addDeadLetterQueue(declarables, deadLetterExchange, RabbitReliableMessaging.CHAT_MEMORY_PERSIST_DLQ);
        addDeadLetterQueue(declarables, deadLetterExchange, RabbitReliableMessaging.USER_MEMORY_DLQ);
        return new Declarables(declarables);
    }

    @Bean
    public Queue chatStreamEventsQueue() {
        return new Queue(QUEUE_CHAT_STREAM_EVENTS, true);
    }

    @Bean(name = CHAT_MEMORY_PERSIST_QUEUE_NAMES_BEAN)
    public String[] chatMemoryPersistQueueNames(@Value(CHAT_MEMORY_PERSIST_QUEUE_PROPERTY) String queueName,
                                                @Value(CHAT_MEMORY_PERSIST_SHARDS_PROPERTY) int shardCount) {
        return ChatMemoryShardSupport.queueNames(queueName, shardCount).toArray(String[]::new);
    }

    @Bean
    public Declarables chatMemoryPersistShardDeadLetterQueues(
            @Value(CHAT_MEMORY_PERSIST_QUEUE_PROPERTY) String queueName,
            @Value(CHAT_MEMORY_PERSIST_SHARDS_PROPERTY) int shardCount) {
        List<Declarable> declarables = new ArrayList<>();
        for (String shardQueueName : ChatMemoryShardSupport.queueNames(queueName, shardCount)) {
            declarables.add(durableQueue(shardQueueName, RabbitReliableMessaging.CHAT_MEMORY_PERSIST_DLQ));
        }
        return new Declarables(declarables);
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
            @Value("${echomind.rabbitmq.chat-request.prefetch:1}") int prefetchCount,
            RabbitTemplate rabbitTemplate,
            RabbitRetryProperties retry) {
        return listenerFactory(connectionFactory, converter, concurrentConsumers, maxConcurrentConsumers, prefetchCount,
            retryAdvice(rabbitTemplate, retry, RabbitReliableMessaging.CHAT_REQUESTS_DLQ));
    }

    @Bean(name = CHAT_STREAM_EVENT_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory chatStreamEventRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            @Value("${echomind.rabbitmq.chat-stream-event.concurrent-consumers:${echomind.rabbitmq.chat-response.concurrent-consumers:4}}") int concurrentConsumers,
            @Value("${echomind.rabbitmq.chat-stream-event.max-concurrent-consumers:${echomind.rabbitmq.chat-response.max-concurrent-consumers:4}}") int maxConcurrentConsumers,
            @Value("${echomind.rabbitmq.chat-stream-event.prefetch:${echomind.rabbitmq.chat-response.prefetch:50}}") int prefetchCount) {
        return listenerFactory(connectionFactory, converter, concurrentConsumers, maxConcurrentConsumers, prefetchCount);
    }

    @Bean(name = CHAT_MEMORY_PERSIST_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory chatMemoryPersistRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            RabbitTemplate rabbitTemplate,
            RabbitRetryProperties retry) {
        // 每个分片队列只能有 1 个 consumer；整体并发靠分片数扩展，保证同一 sessionId 不乱序。
        return listenerFactory(connectionFactory, converter, 1, 1, 1,
            retryAdvice(rabbitTemplate, retry, RabbitReliableMessaging.CHAT_MEMORY_PERSIST_DLQ));
    }

    @Bean
    public RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        RabbitReliableMessaging.configureTemplate(template, "echomind-console");
        return template;
    }

    @Bean
    public RabbitRetryProperties rabbitRetryProperties(
            @Value("${echomind.rabbitmq.retry.max-attempts:3}") int maxAttempts,
            @Value("${echomind.rabbitmq.retry.initial-interval-ms:1000}") long initialIntervalMs,
            @Value("${echomind.rabbitmq.retry.multiplier:5}") double multiplier,
            @Value("${echomind.rabbitmq.retry.max-interval-ms:30000}") long maxIntervalMs) {
        return new RabbitRetryProperties(maxAttempts, initialIntervalMs, multiplier, maxIntervalMs);
    }

    private SimpleRabbitListenerContainerFactory listenerFactory(ConnectionFactory connectionFactory,
                                                                 Jackson2JsonMessageConverter converter,
                                                                 int concurrentConsumers,
                                                                 int maxConcurrentConsumers,
                                                                 int prefetchCount) {
        return listenerFactory(connectionFactory, converter, concurrentConsumers, maxConcurrentConsumers, prefetchCount,
            new Advice[0]);
    }

    private SimpleRabbitListenerContainerFactory listenerFactory(ConnectionFactory connectionFactory,
                                                                 Jackson2JsonMessageConverter converter,
                                                                 int concurrentConsumers,
                                                                 int maxConcurrentConsumers,
                                                                 int prefetchCount,
                                                                 Advice... advice) {
        int consumers = Math.max(1, concurrentConsumers);
        int maxConsumers = Math.max(consumers, maxConcurrentConsumers);
        int prefetch = Math.max(1, prefetchCount);

        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setConcurrentConsumers(consumers);
        factory.setMaxConcurrentConsumers(maxConsumers);
        factory.setPrefetchCount(prefetch);
        factory.setDefaultRequeueRejected(false);
        if (advice != null && advice.length > 0) {
            factory.setAdviceChain(advice);
        }
        return factory;
    }

    private Advice retryAdvice(RabbitTemplate rabbitTemplate, RabbitRetryProperties retry, String deadLetterRoutingKey) {
        return RetryInterceptorBuilder.stateless()
            .maxAttempts(Math.max(1, retry.maxAttempts()))
            .backOffOptions(
                Math.max(1, retry.initialIntervalMs()),
                Math.max(1.0, retry.multiplier()),
                Math.max(retry.initialIntervalMs(), retry.maxIntervalMs())
            )
            .recoverer(new RepublishMessageRecoverer(
                rabbitTemplate,
                RabbitReliableMessaging.DEAD_LETTER_EXCHANGE,
                deadLetterRoutingKey
            ))
            .build();
    }

    private Queue durableQueue(String queueName, String deadLetterRoutingKey) {
        return new Queue(queueName, true, false, false, Map.of(
            "x-dead-letter-exchange", RabbitReliableMessaging.DEAD_LETTER_EXCHANGE,
            "x-dead-letter-routing-key", deadLetterRoutingKey
        ));
    }

    private void addDeadLetterQueue(List<Declarable> declarables, DirectExchange deadLetterExchange, String queueName) {
        Queue queue = new Queue(queueName, true);
        Binding binding = BindingBuilder.bind(queue).to(deadLetterExchange).with(queueName);
        declarables.add(queue);
        declarables.add(binding);
    }

    public record RabbitRetryProperties(int maxAttempts, long initialIntervalMs, double multiplier, long maxIntervalMs) {
    }
}
