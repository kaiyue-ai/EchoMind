package com.echomind.console.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MessageAttachment;
import com.echomind.memory.MemoryManager;
import com.echomind.skill.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 记忆应用服务测试。
 *
 * <p>重点确认记忆入口按当前用户和 sessionId 工作：Web 层传入非法会话时不触碰 MemoryManager，
 * 合法请求则直接委托给当前“用户 + 一次对话一份记忆”的事实来源。</p>
 */
class MemoryApplicationServiceTest {

    @Test
    void getMemoryDelegatesToMemoryManager() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryApplicationService service = new MemoryApplicationService(memoryManager, mock(ObjectStorageService.class));
        AgentMessage message = new AgentMessage("user", "你好", Instant.now(), null);
        when(memoryManager.getFullContext("default", "session-1")).thenReturn(List.of(message));

        List<AgentMessage> result = service.getMemory("session-1");

        assertThat(result).containsExactly(message);
    }

    @Test
    void getChatHistoryRefreshesManagedAttachmentUrls() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        MemoryApplicationService service = new MemoryApplicationService(memoryManager, storageService);
        MessageAttachment attachment = MessageAttachment.image(
            "oss://bucket/chat/a.png",
            "https://old.example.com/a.png",
            "image/png",
            "a.png",
            10L
        );
        when(memoryManager.getFullContext("default", "session-1")).thenReturn(List.of(
            new AgentMessage("user", "看图", Instant.now(), null, List.of(attachment))
        ));
        when(storageService.supports("oss://bucket/chat/a.png")).thenReturn(true);
        when(storageService.urlFor("oss://bucket/chat/a.png", Duration.ofDays(7)))
            .thenReturn("https://signed.example.com/a.png");

        List<AgentMessage> result = service.getChatHistory("session-1");

        assertThat(result.get(0).attachments().get(0).url()).isEqualTo("https://signed.example.com/a.png");
    }

    @Test
    void clearMemoryValidatesSessionIdBeforeDeleting() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryApplicationService service = new MemoryApplicationService(memoryManager, mock(ObjectStorageService.class));

        Map<String, String> result = service.clearMemory("session-1");

        verify(memoryManager).clearSession("default", "session-1");
        assertThat(result).containsEntry("status", "cleared");
        assertThat(result).containsEntry("sessionId", "session-1");
    }

    @Test
    void blankSessionIdIsRejected() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryApplicationService service = new MemoryApplicationService(memoryManager, mock(ObjectStorageService.class));

        assertThatThrownBy(() -> service.getMemory(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sessionId不能为空");

        verifyNoInteractions(memoryManager);
    }
}
