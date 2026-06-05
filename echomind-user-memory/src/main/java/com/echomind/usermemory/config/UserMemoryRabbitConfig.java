package com.echomind.usermemory.config;

import com.echomind.common.messaging.RabbitQueueNames;
import com.echomind.common.observability.EchoMindTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
@Slf4j
public class UserMemoryRabbitConfig {

    @Bean
    public Queue userMemoryQueue(UserMemoryProperties properties) {
        return new Queue(properties.getQueueName(), true, false, false, Map.of(
            "x-dead-letter-exchange", RabbitQueueNames.DEAD_LETTER_EXCHANGE,
            "x-dead-letter-routing-key", RabbitQueueNames.USER_MEMORY_DLQ
        ));
    }

    @Bean
    public DirectExchange userMemoryDeadLetterExchange() {
        return new DirectExchange(RabbitQueueNames.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Declarables userMemoryDeadLetterQueue(DirectExchange userMemoryDeadLetterExchange) {
        Queue queue = new Queue(RabbitQueueNames.USER_MEMORY_DLQ, true);
        Binding binding = BindingBuilder.bind(queue)
            .to(userMemoryDeadLetterExchange)
            .with(RabbitQueueNames.USER_MEMORY_DLQ);
        List<Declarable> declarables = new ArrayList<>();
        declarables.add(queue);
        declarables.add(binding);
        return new Declarables(declarables);
    }

    @Bean
    public Jackson2JsonMessageConverter userMemoryMessageConverter(ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    public RabbitTemplate userMemoryRabbitTemplate(ConnectionFactory connectionFactory,
                                                   Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        configureTemplate(template);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            RabbitTemplate userMemoryRabbitTemplate,
            UserMemoryRetryProperties retry) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryAdvice(userMemoryRabbitTemplate, retry));
        return factory;
    }

    @Bean
    public UserMemoryRetryProperties userMemoryRetryProperties(
            @Value("${echomind.rabbitmq.retry.max-attempts:3}") int maxAttempts,
            @Value("${echomind.rabbitmq.retry.initial-interval-ms:1000}") long initialIntervalMs,
            @Value("${echomind.rabbitmq.retry.multiplier:5}") double multiplier,
            @Value("${echomind.rabbitmq.retry.max-interval-ms:30000}") long maxIntervalMs) {
        return new UserMemoryRetryProperties(maxAttempts, initialIntervalMs, multiplier, maxIntervalMs);
    }

    private Advice retryAdvice(RabbitTemplate rabbitTemplate, UserMemoryRetryProperties retry) {
        return RetryInterceptorBuilder.stateless()
            .maxAttempts(Math.max(1, retry.maxAttempts()))
            .backOffOptions(
                Math.max(1, retry.initialIntervalMs()),
                Math.max(1.0, retry.multiplier()),
                Math.max(retry.initialIntervalMs(), retry.maxIntervalMs())
            )
            .recoverer(new RepublishMessageRecoverer(
                rabbitTemplate,
                RabbitQueueNames.DEAD_LETTER_EXCHANGE,
                RabbitQueueNames.USER_MEMORY_DLQ
            ))
            .build();
    }

    private void configureTemplate(RabbitTemplate template) {
        template.setMandatory(true);
        template.setConfirmCallback((correlation, ack, cause) -> {
            if (ack) {
                return;
            }
            RuntimeException failure = new IllegalStateException("RabbitMQ publisher confirm failed owner=echomind-user-memory"
                + " correlationId=" + (correlation == null ? "" : correlation.getId())
                + " cause=" + (cause == null ? "" : cause));
            EchoMindTrace.recordException(EchoMindTrace.currentSpan(), failure);
            log.warn("RabbitMQ publisher confirm failed owner=echomind-user-memory correlationId={} cause={}",
                correlation == null ? "" : correlation.getId(), cause == null ? "" : cause);
        });
        template.setReturnsCallback(returned -> {
            RuntimeException failure = new IllegalStateException("RabbitMQ message returned owner=echomind-user-memory"
                + " exchange=" + returned.getExchange()
                + " routingKey=" + returned.getRoutingKey()
                + " replyCode=" + returned.getReplyCode()
                + " replyText=" + returned.getReplyText());
            EchoMindTrace.recordException(EchoMindTrace.currentSpan(), failure);
            log.warn("RabbitMQ message returned owner=echomind-user-memory exchange={} routingKey={} replyCode={} replyText={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText());
        });
    }

    public record UserMemoryRetryProperties(int maxAttempts, long initialIntervalMs, double multiplier,
                                            long maxIntervalMs) {
    }
}
