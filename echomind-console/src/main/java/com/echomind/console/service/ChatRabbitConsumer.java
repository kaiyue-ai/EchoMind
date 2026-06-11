package com.echomind.console.service;

import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatResponse;
import com.echomind.common.model.ChatStreamEvent;
import com.echomind.console.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatRabbitConsumer {

    private final ChatApplicationService chatService; // 聊天应用服务
    private final SsePushService ssePushService; // SSE 推送服务

    @RabbitListener(
        queues = RabbitMQConfig.QUEUE_CHAT_REQUESTS, // 监听队列
        containerFactory = RabbitMQConfig.CHAT_REQUEST_LISTENER_FACTORY // 容器工厂
    )
    public void onChatRequest(ChatRequest request) {
        log.info("Processing chat request: {}", request.requestId());
        ChatResponse response = chatService.executeQueuedStream(request, ssePushService::pushEvent);
        ChatStreamEvent terminal = "OK".equals(response.status())
            ? ChatStreamEvent.result(response)
            : ChatStreamEvent.failure(response.requestId(), response.error(), response.errorDetail(),
                response.traceId());
        terminal = terminal.withTrace(response.traceId(), firstNonBlank(response.traceparent(), request.traceparent()));
        ssePushService.pushEvent(terminal);
        log.info("Published response for request {}", request.requestId());
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
