package com.echomind.console.service;

import com.echomind.common.model.ChatResponse;
import com.echomind.console.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SsePushService {

    private static final long SSE_TIMEOUT_MS = 300_000;

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(String requestId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(requestId, emitter);
        emitter.onCompletion(() -> emitters.remove(requestId));
        emitter.onTimeout(() -> {
            emitters.remove(requestId);
            log.info("SSE emitter timed out for request {}", requestId);
        });
        emitter.onError(e -> {
            emitters.remove(requestId);
            log.warn("SSE emitter error for request {}: {}", requestId, e.getMessage());
        });
        return emitter;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CHAT_RESPONSES)
    public void onChatResponse(ChatResponse response) {
        SseEmitter emitter = emitters.remove(response.requestId());
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                    .name("result")
                    .data(response));
                emitter.complete();
                log.info("SSE pushed result for request {}", response.requestId());
            } catch (IOException e) {
                emitter.completeWithError(e);
                log.error("SSE send failed for {}: {}", response.requestId(), e.getMessage());
            }
        } else {
            log.debug("No SSE subscriber for request {}", response.requestId());
        }
    }
}
