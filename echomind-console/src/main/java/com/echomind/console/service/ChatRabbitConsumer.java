package com.echomind.console.service;

import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatResponse;
import com.echomind.common.model.ChatStreamEvent;
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

    private final ChatApplicationService chatService; // 聊天应用服务
    private final RabbitTemplate template; // RabbitMQ 模板

    @RabbitListener(
        queues = RabbitMQConfig.QUEUE_CHAT_REQUESTS, // 监听队列
        containerFactory = RabbitMQConfig.CHAT_REQUEST_LISTENER_FACTORY // 容器工厂
    )
    public void onChatRequest(ChatRequest request) {
        log.info("Processing chat request: {}", request.requestId());
        // 1. 执行队列流式聊天：将请求ID发送到队列，等待异步处理
        ChatResponse response = chatService.executeQueuedStream(request, event ->
            template.convertAndSend(RabbitMQConfig.QUEUE_CHAT_STREAM_EVENTS, event));
        ChatStreamEvent terminal = "OK".equals(response.status())
            ? ChatStreamEvent.result(response)
            : ChatStreamEvent.failure(response.requestId(), response.error(), response.traceId());
        terminal = terminal.withTrace(response.traceId(), firstNonBlank(response.traceparent(), request.traceparent()));
        template.convertAndSend(RabbitMQConfig.QUEUE_CHAT_STREAM_EVENTS, terminal);
        log.info("Published response for request {}", request.requestId());
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
