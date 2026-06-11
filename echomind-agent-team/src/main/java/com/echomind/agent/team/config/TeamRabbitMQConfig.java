package com.echomind.agent.team.config;

import com.echomind.agent.messaging.RabbitReliableMessaging;
import com.echomind.common.messaging.RabbitQueueNames;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
@ConditionalOnClass(RabbitTemplate.class)
public class TeamRabbitMQConfig {

    public static final String TEAM_EXCHANGE = "echomind.team.exchange";
    public static final String RUN_EVENT_ROUTING_PREFIX = "run.shard.";
    public static final String RUN_EVENT_QUEUE_PREFIX = "echomind.team.run.events.shard.";
    public static final String STEP_EXECUTE_QUEUE = "echomind.team.step.execute";
    public static final String STEP_EXECUTE_ROUTING_KEY = "step.execute";
    public static final String TEAM_RUN_EVENTS_DLQ = "echomind.team.run-events.dlq";
    public static final String TEAM_STEP_EXECUTE_DLQ = "echomind.team.step-execute.dlq";

    public static final String TEAM_RUN_EVENT_LISTENER_FACTORY = "teamRunEventListenerFactory";
    public static final String TEAM_STEP_EXECUTE_LISTENER_FACTORY = "teamStepExecuteListenerFactory";
    public static final String TEAM_RUN_EVENT_SHARD_QUEUES_BEAN = "teamRunEventShardQueues";
    public static final String TEAM_DEAD_LETTER_QUEUES_BEAN = "teamDeadLetterQueues";

    private static final int DEFAULT_SHARDS = 4;

    // ---- Exchange ----

    @Bean
    public DirectExchange teamExchange() {
        return new DirectExchange(TEAM_EXCHANGE, true, false);
    }

    // ---- Run Event Shard Queues ----

    @Bean(name = TEAM_RUN_EVENT_SHARD_QUEUES_BEAN)
    public String[] teamRunEventShardQueues(
            @Value("${echomind.team.runtime.dag-event-shards:" + DEFAULT_SHARDS + "}") int shards) {
        int n = Math.max(1, Math.min(shards, 16));
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            names[i] = RUN_EVENT_QUEUE_PREFIX + i;
        }
        return names;
    }

    @Bean
    public Declarables teamRunEventShardQueuesDeclarables(
            DirectExchange teamExchange,
            @Value("${echomind.team.runtime.dag-event-shards:" + DEFAULT_SHARDS + "}") int shards) {
        int n = Math.max(1, Math.min(shards, 16));
        List<Declarable> declarables = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String queueName = RUN_EVENT_QUEUE_PREFIX + i;
            String routingKey = RUN_EVENT_ROUTING_PREFIX + i;
            Queue queue = new Queue(queueName, true, false, false, Map.of(
                "x-dead-letter-exchange", RabbitReliableMessaging.DEAD_LETTER_EXCHANGE,
                "x-dead-letter-routing-key", TEAM_RUN_EVENTS_DLQ
            ));
            Binding binding = BindingBuilder.bind(queue).to(teamExchange).with(routingKey);
            declarables.add(queue);
            declarables.add(binding);
        }
        return new Declarables(declarables);
    }

    // ---- Step Execute Queue ----

    @Bean
    public Queue teamStepExecuteQueue() {
        return new Queue(STEP_EXECUTE_QUEUE, true, false, false, Map.of(
            "x-dead-letter-exchange", RabbitReliableMessaging.DEAD_LETTER_EXCHANGE,
            "x-dead-letter-routing-key", TEAM_STEP_EXECUTE_DLQ
        ));
    }

    @Bean
    public Binding teamStepExecuteBinding(DirectExchange teamExchange, Queue teamStepExecuteQueue) {
        return BindingBuilder.bind(teamStepExecuteQueue).to(teamExchange).with(STEP_EXECUTE_ROUTING_KEY);
    }

    // ---- Dead Letter Queue Names (for RabbitDeadLetterConsumer) ----

    @Bean(name = TEAM_DEAD_LETTER_QUEUES_BEAN)
    public String[] teamDeadLetterQueues() {
        return new String[]{TEAM_RUN_EVENTS_DLQ, TEAM_STEP_EXECUTE_DLQ};
    }

    // ---- Consumer Factories (no RetryInterceptor — use requeue + x-death counting) ----

    @Bean(name = TEAM_RUN_EVENT_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory teamRunEventListenerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean(name = TEAM_STEP_EXECUTE_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory teamStepExecuteListenerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            @Value("${echomind.team.runtime.step-execute-consumers:10}") int consumers) {
        int c = Math.max(1, Math.min(consumers, 50));
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setConcurrentConsumers(c);
        factory.setMaxConcurrentConsumers(c);
        factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    // ---- Team RabbitTemplate ----

    @Bean("teamRabbitTemplate")
    public RabbitTemplate teamRabbitTemplate(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        RabbitReliableMessaging.configureTemplate(template, "echomind-agent-team");
        return template;
    }

    // ---- Routing helper ----

    public static int shardForRun(String runId, int shardCount) {
        return Math.abs(runId.hashCode()) % shardCount;
    }

    public static String routingKeyForRun(String runId, int shardCount) {
        return RUN_EVENT_ROUTING_PREFIX + shardForRun(runId, shardCount);
    }
}
