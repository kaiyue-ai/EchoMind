package com.echomind.usermemory.consumer;

import com.echomind.usermemory.config.UserMemoryProperties;
import com.echomind.usermemory.service.UserMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期扫描用户级全局缓冲区，避免重要但未满批次的对话长期滞留。 */
@Component
@RequiredArgsConstructor
public class UserMemoryIdleFlushScheduler {

    private final UserMemoryService userMemoryService;
    private final UserMemoryProperties properties;

    @Scheduled(fixedDelayString = "#{${echomind.user-memory.scan-idle-buffers-seconds:60} * 1000L}")
    public void flushIdleBuffers() {
        if (properties.isEnabled()) {
            userMemoryService.flushIdleBuffers();
        }
    }
}
