package com.echomind.usermemory.consumer;

import com.echomind.common.model.UserMemoryEvent;
import com.echomind.usermemory.config.UserMemoryProperties;
import com.echomind.usermemory.service.UserMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserMemoryConsumer {

    private final UserMemoryService userMemoryService;
    private final UserMemoryProperties properties;

    @RabbitListener(queues = "#{@userMemoryQueue.name}")
    public void onEvent(UserMemoryEvent event) {
        try {
            userMemoryService.process(event);
        } catch (Exception e) {
            String sessionId = event == null ? null : event.sessionId();
            log.warn("Failed to process user memory event sessionId={}: {}", sessionId, e.getMessage());
            if (properties.isEnabled()) {
                throw new IllegalStateException(e);
            }
        }
    }
}
