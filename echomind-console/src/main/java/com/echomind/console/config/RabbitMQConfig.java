package com.echomind.console.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String QUEUE_CHAT_REQUESTS = "echomind.chat.requests";
    public static final String QUEUE_CHAT_RESPONSES = "echomind.chat.responses";

    @Bean
    public Queue chatRequestsQueue() {
        return new Queue(QUEUE_CHAT_REQUESTS, true);
    }

    @Bean
    public Queue chatResponsesQueue() {
        return new Queue(QUEUE_CHAT_RESPONSES, true);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(
            org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
