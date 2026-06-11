package com.echomind.console.config;

import com.echomind.common.messaging.ChatMemoryShardSupport;
import com.echomind.common.messaging.RabbitQueueNames;
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
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RabbitMQ 消息队列配置类
 *
 * <p>负责声明所有业务队列、死信队列、消费者工厂和重试策略。
 *
 * <h2>队列架构</h2>
 * <pre>
 * 用户请求 → [chatRequestsQueue] → ChatRabbitConsumer 处理
 *                    ↓ (处理失败)
 *              [chatRequestsDLQ] (死信队列)
 *
 * 记忆持久化 → [chatMemoryPersistQueue.shard.N] → MemoryPersistConsumer 处理
 *                    ↓ (处理失败)
 *              [chatMemoryPersistDLQ] (死信队列)
 * </pre>
 *
 * <h2>重试策略</h2>
 * <ul>
 *   <li>最大重试次数: 3次</li>
 *   <li>重试间隔: 1s → 5s → 25s (指数退避)</li>
 *   <li>重试失败后: 进入死信队列(DLQ)持久化</li>
 * </ul>
 */
@Configuration
public class RabbitMQConfig {

    // ==================== 队列名称常量 ====================

    /** 聊天请求队列：存放用户的异步聊天请求 */
    public static final String QUEUE_CHAT_REQUESTS = "echomind.chat.requests";

    /** 聊天记忆持久化队列基础名称（实际使用时会被分片） */
    public static final String QUEUE_CHAT_MEMORY_PERSIST_REQUESTS = "echomind.chat-memory.persist.requests";

    /** 记忆持久化队列名称配置：可覆盖，默认使用 QUEUE_CHAT_MEMORY_PERSIST_REQUESTS */
    public static final String CHAT_MEMORY_PERSIST_QUEUE_PROPERTY =
        "${echomind.memory.persist-queue-name:" + QUEUE_CHAT_MEMORY_PERSIST_REQUESTS + "}";

    /** 记忆持久化分片数量配置：可覆盖，默认8个分片 */
    public static final String CHAT_MEMORY_PERSIST_SHARDS_PROPERTY = "${echomind.memory.persist-shards:8}";

    // ==================== 消费者工厂 Bean 名称常量 ====================

    /** 聊天请求消费者工厂名称 */
    public static final String CHAT_REQUEST_LISTENER_FACTORY = "chatRequestRabbitListenerContainerFactory";

    /** 记忆持久化消费者工厂名称 */
    public static final String CHAT_MEMORY_PERSIST_LISTENER_FACTORY = "chatMemoryPersistRabbitListenerContainerFactory";

    /** 死信队列消费者工厂名称 */
    public static final String DEAD_LETTER_LISTENER_FACTORY = "deadLetterRabbitListenerContainerFactory";

    /** 记忆持久化分片队列名称数组的 Bean 名称 */
    public static final String CHAT_MEMORY_PERSIST_QUEUE_NAMES_BEAN = "chatMemoryPersistQueueNames";

    // ==================== 队列声明 ====================

    /**
     * 声明聊天请求队列
     *
     * <p>用于接收用户的异步聊天请求消息。
     * 队列配置了死信交换机，消息处理失败时会进入 DLQ。
     *
     * @return 持久化的聊天请求队列
     */
    @Bean
    public Queue chatRequestsQueue() {
        return durableQueue(QUEUE_CHAT_REQUESTS, RabbitReliableMessaging.CHAT_REQUESTS_DLQ);
    }

    // ==================== 死信交换机和死信队列声明 ====================

    /**
     * 声明死信交换机 (DLX)
     *
     * <p>所有业务队列配置了 x-dead-letter-exchange 后，
     * 处理失败的消息会被路由到这个交换机。
     *
     * @return 持久化的直接类型交换机
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(RabbitReliableMessaging.DEAD_LETTER_EXCHANGE, true, false);
    }

    /**
     * 声明控制台相关的死信队列并绑定到死信交换机
     *
     * <p>包括：聊天请求 DLQ、记忆持久化 DLQ、用户记忆 DLQ
     *
     * @param deadLetterExchange 死信交换机
     * @return 声明的队列和绑定集合
     */
    @Bean
    public Declarables consoleDeadLetterQueues(DirectExchange deadLetterExchange) {
        List<Declarable> declarables = new ArrayList<>();
        // 添加聊天请求死信队列
        addDeadLetterQueue(declarables, deadLetterExchange, RabbitReliableMessaging.CHAT_REQUESTS_DLQ);
        // 添加记忆持久化死信队列
        addDeadLetterQueue(declarables, deadLetterExchange, RabbitReliableMessaging.CHAT_MEMORY_PERSIST_DLQ);
        // 添加用户记忆死信队列
        addDeadLetterQueue(declarables, deadLetterExchange, RabbitReliableMessaging.USER_MEMORY_DLQ);
        // 添加 Team 死信队列
        addDeadLetterQueue(declarables, deadLetterExchange, RabbitQueueNames.TEAM_RUN_EVENTS_DLQ);
        addDeadLetterQueue(declarables, deadLetterExchange, RabbitQueueNames.TEAM_STEP_EXECUTE_DLQ);
        return new Declarables(declarables);
    }

    /**
     * 生成记忆持久化分片队列名称数组
     *
     * <p>用于动态创建多个分片队列，实现并行处理。
     * 例如：8个分片 → [echomind.chat-memory.persist.requests.shard.0, ..., shard.7]
     *
     * @param queueName 队列基础名称
     * @param shardCount 分片数量
     * @return 分片队列名称数组
     */
    @Bean(name = CHAT_MEMORY_PERSIST_QUEUE_NAMES_BEAN)
    public String[] chatMemoryPersistQueueNames(@Value(CHAT_MEMORY_PERSIST_QUEUE_PROPERTY) String queueName,
                                                @Value(CHAT_MEMORY_PERSIST_SHARDS_PROPERTY) int shardCount) {
        return ChatMemoryShardSupport.queueNames(queueName, shardCount).toArray(String[]::new);
    }

    /**
     * 声明记忆持久化分片队列的死信队列
     *
     * <p>每个分片队列都有对应的死信队列。
     *
     * @param queueName 队列基础名称
     * @param shardCount 分片数量
     * @return 声明的死信队列集合
     */
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

    // ==================== 消息转换器 ====================

    /**
     * 声明 JSON 消息转换器
     *
     * <p>使用 Jackson 将 Java 对象与 JSON 字符串互相转换。
     * 所有生产者和消费者都使用此转换器，保证消息格式一致。
     *
     * @param mapper Spring 注入的 ObjectMapper
     * @return Jackson JSON 消息转换器
     */
    @Bean
    public Jackson2JsonMessageConverter messageConverter(ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper);
    }

    // ==================== 消费者工厂 ====================

    /**
     * 聊天请求消费者工厂
     *
     * <p>配置：
     * <ul>
     *   <li>并发数: 20（可配置）</li>
     *   <li>Prefetch: 1（保证公平分发）</li>
     *   <li>重试机制: 最多3次，失败后进入 DLQ</li>
     * </ul>
     *
     * @param connectionFactory RabbitMQ 连接工厂
     * @param converter 消息转换器
     * @param concurrentConsumers 并发消费者数
     * @param maxConcurrentConsumers 最大并发消费者数
     * @param prefetchCount 预取消息数量
     * @param rabbitTemplate RabbitMQ 模板（用于重试时发布到 DLQ）
     * @param retry 重试配置参数
     * @return 配置好的消费者容器工厂
     */
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

    /**
     * 记忆持久化消费者工厂
     *
     * <p>配置：
     * <ul>
     *   <li>并发数: 1（每个分片只能有1个消费者）</li>
     *   <li>Prefetch: 1</li>
     *   <li>重试机制: 最多3次，失败后进入 DLQ</li>
     * </ul>
     *
     * <p><b>重要</b>：分片队列保证同一 sessionId 的消息按顺序处理。
     * 整体并发靠分片数扩展，而不是单队列并发。
     *
     * @param connectionFactory RabbitMQ 连接工厂
     * @param converter 消息转换器
     * @param rabbitTemplate RabbitMQ 模板（用于重试时发布到 DLQ）
     * @param retry 重试配置参数
     * @return 配置好的消费者容器工厂
     */
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

    /**
     * 死信队列消费者工厂
     *
     * <p>用于消费死信队列中的消息，进行告警或人工处理。
     * 配置为手动确认模式，确保消息不会丢失。
     *
     * @param connectionFactory RabbitMQ 连接工厂
     * @param converter 消息转换器
     * @param prefetchCount 预取消息数量
     * @return 配置好的消费者容器工厂
     */
    @Bean(name = DEAD_LETTER_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory deadLetterRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            @Value("${echomind.rabbitmq.dead-letter.prefetch:1}") int prefetchCount) {
        var factory = listenerFactory(connectionFactory, converter, 1, 1, Math.max(1, prefetchCount));
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }

    // ==================== RabbitTemplate ====================

    /**
     * 声明 RabbitMQ 模板
     *
     * <p>用于发送消息到队列。
     * 配置了：
     * <ul>
     *   <li>JSON 消息转换器</li>
     *   <li>Publisher Confirm（确认消息到达 Broker）</li>
     *   <li>Publisher Return（确认消息路由到队列）</li>
     * </ul>
     *
     * @param connectionFactory RabbitMQ 连接工厂
     * @param converter 消息转换器
     * @return 配置好的 RabbitMQ 模板
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        RabbitReliableMessaging.configureTemplate(template, "echomind-console");
        return template;
    }

    // ==================== 重试配置 ====================

    /**
     * 声明重试配置参数
     *
     * <p>使用指数退避策略：
     * <ul>
     *   <li>初始间隔: 1000ms</li>
     *   <li>间隔倍数: 5（1s → 5s → 25s）</li>
     *   <li>最大间隔: 30000ms（30秒）</li>
     * </ul>
     *
     * @param maxAttempts 最大重试次数
     * @param initialIntervalMs 初始重试间隔（毫秒）
     * @param multiplier 间隔倍数
     * @param maxIntervalMs 最大重试间隔（毫秒）
     * @return 重试配置参数
     */
    @Bean
    public RabbitRetryProperties rabbitRetryProperties(
            @Value("${echomind.rabbitmq.retry.max-attempts:3}") int maxAttempts,
            @Value("${echomind.rabbitmq.retry.initial-interval-ms:1000}") long initialIntervalMs,
            @Value("${echomind.rabbitmq.retry.multiplier:5}") double multiplier,
            @Value("${echomind.rabbitmq.retry.max-interval-ms:30000}") long maxIntervalMs) {
        return new RabbitRetryProperties(maxAttempts, initialIntervalMs, multiplier, maxIntervalMs);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 创建消费者容器工厂（无重试机制）
     *
     * @param connectionFactory RabbitMQ 连接工厂
     * @param converter 消息转换器
     * @param concurrentConsumers 并发消费者数
     * @param maxConcurrentConsumers 最大并发消费者数
     * @param prefetchCount 预取消息数量
     * @return 配置好的消费者容器工厂
     */
    private SimpleRabbitListenerContainerFactory listenerFactory(ConnectionFactory connectionFactory,
                                                                 Jackson2JsonMessageConverter converter,
                                                                 int concurrentConsumers,
                                                                 int maxConcurrentConsumers,
                                                                 int prefetchCount) {
        return listenerFactory(connectionFactory, converter, concurrentConsumers, maxConcurrentConsumers, prefetchCount,
            new Advice[0]);
    }

    /**
     * 创建消费者容器工厂（可配置重试机制）
     *
     * <p>配置项：
     * <ul>
     *   <li>connectionFactory: RabbitMQ 连接</li>
     *   <li>messageConverter: 消息转换器</li>
     *   <li>concurrentConsumers: 最小并发数</li>
     *   <li>maxConcurrentConsumers: 最大并发数</li>
     *   <li>prefetchCount: 预取数量</li>
     *   <li>advice: 可选的重试拦截器</li>
     * </ul>
     *
     * @param connectionFactory RabbitMQ 连接工厂
     * @param converter 消息转换器
     * @param concurrentConsumers 并发消费者数
     * @param maxConcurrentConsumers 最大并发消费者数
     * @param prefetchCount 预取消息数量
     * @param advice 可选的重试拦截器数组
     * @return 配置好的消费者容器工厂
     */
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
        // 设置拒绝的消息不重新入队，而是走死信流程
        factory.setDefaultRequeueRejected(false);
        if (advice != null && advice.length > 0) {
            factory.setAdviceChain(advice);
        }
        return factory;
    }

    /**
     * 创建重试拦截器
     *
     * <p>使用指数退避策略：
     * <pre>
     * 第1次重试: 1s
     * 第2次重试: 5s
     * 第3次重试: 25s
     * </pre>
     * 重试失败后，消息会被重新发布到死信交换机。
     *
     * @param rabbitTemplate RabbitMQ 模板（用于发布到 DLQ）
     * @param retry 重试配置参数
     * @param deadLetterRoutingKey 死信路由键
     * @return 配置好的重试拦截器
     */
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

    /**
     * 创建持久化队列（带死信配置）
     *
     * <p>队列参数：
     * <ul>
     *   <li>durable = true: 队列持久化，RabbitMQ 重启后队列仍存在</li>
     *   <li>exclusive = false: 非独占，允许其他连接使用</li>
     *   <li>autoDelete = false: 非自动删除，无消费者时也不删除</li>
     *   <li>x-dead-letter-exchange: 死信交换机名称</li>
     *   <li>x-dead-letter-routing-key: 死信路由键</li>
     * </ul>
     *
     * @param queueName 队列名称
     * @param deadLetterRoutingKey 死信路由键
     * @return 配置好的持久化队列
     */
    private Queue durableQueue(String queueName, String deadLetterRoutingKey) {
        return new Queue(queueName, true, false, false, Map.of(
            "x-dead-letter-exchange", RabbitReliableMessaging.DEAD_LETTER_EXCHANGE,
            "x-dead-letter-routing-key", deadLetterRoutingKey
        ));
    }

    /**
     * 添加死信队列及其与死信交换机的绑定
     *
     * @param declarables 声明列表（添加队列和绑定）
     * @param deadLetterExchange 死信交换机
     * @param queueName 死信队列名称
     */
    private void addDeadLetterQueue(List<Declarable> declarables, DirectExchange deadLetterExchange, String queueName) {
        // 创建持久化的死信队列
        Queue queue = new Queue(queueName, true);
        // 绑定队列到死信交换机，路由键为队列名称
        Binding binding = BindingBuilder.bind(queue).to(deadLetterExchange).with(queueName);
        declarables.add(queue);
        declarables.add(binding);
    }

    /**
     * 重试配置参数记录类
     *
     * @param maxAttempts 最大重试次数
     * @param initialIntervalMs 初始重试间隔（毫秒）
     * @param multiplier 间隔倍数（指数退避）
     * @param maxIntervalMs 最大重试间隔（毫秒）
     */
    public record RabbitRetryProperties(int maxAttempts, long initialIntervalMs, double multiplier, long maxIntervalMs) {
    }
}
