package com.echomind.console.service;

import com.echomind.common.model.ChatRequest;
import com.echomind.agent.messaging.RabbitReliableMessaging;
import com.echomind.console.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatRabbitProducer {

    private final RabbitTemplate template;

    // 现在是一个生产者对应一个消息队列，所以发给消息队列比较直接
    // 需要交换机的情况：就比如说是一个消费者对应多个消息队列
    // 我们需要对用户进行区分，普通用户和VIP用户去的队列不同，这个时候就需要交换机进行路由
    public void publish(ChatRequest request) {
        template.convertAndSend(
            RabbitMQConfig.QUEUE_CHAT_REQUESTS,
            request,
            RabbitReliableMessaging.persistentMessage(),
            RabbitReliableMessaging.correlation("chat-request", request == null ? null : request.requestId())
        );
        log.info("Published chat request {}", request == null ? null : request.requestId());
    }
}
