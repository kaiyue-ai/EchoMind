package com.echomind.console.service;

import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatResponse;
import com.echomind.console.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatRabbitConsumer {

    private final ChatApplicationService chatService;
    private final RabbitTemplate template;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CHAT_REQUESTS)
    public void onChatRequest(ChatRequest request) {
        log.info("Processing chat request: {}", request.requestId());
        ChatResponse response = chatService.executeQueued(request);
        template.convertAndSend(RabbitMQConfig.QUEUE_CHAT_RESPONSES, response);
        log.info("Published response for request {}", request.requestId());
    }
}
