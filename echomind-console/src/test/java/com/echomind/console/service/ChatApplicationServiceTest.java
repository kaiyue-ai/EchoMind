package com.echomind.console.service;

import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MessageAttachment;
import com.echomind.memory.MemoryManager;
import com.echomind.skill.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 聊天应用服务测试。
 *
 * <p>重点覆盖单条会话删除：历史清理是主流程，附件对象回收是附带清理动作，
 * 即使对象存储删除失败，也不能回滚已删除的会话历史。</p>
 */
class ChatApplicationServiceTest {

    @Test
    void deleteSessionClearsHistoryAndAttachments() throws IOException {
        MemoryManager memoryManager = mock(MemoryManager.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        MessageAttachment kept = MessageAttachment.image(
            "https://example.com/not-managed.png",
            "https://example.com/not-managed.png",
            "image/png",
            "not-managed.png",
            1L
        );
        MessageAttachment deleted = MessageAttachment.image(
            "oss://my-dev-oss/chat-images/a.png",
            "https://signed.example.com/a.png",
            "image/png",
            "a.png",
            2L
        );
        when(memoryManager.getFullContext("session-1")).thenReturn(List.of(
            new AgentMessage("user", "你好", Instant.now(), null, List.of(kept, deleted)),
            new AgentMessage("assistant", "你好呀", Instant.now(), null, null)
        ));
        when(storageService.supports("https://example.com/not-managed.png")).thenReturn(false);
        when(storageService.supports("oss://my-dev-oss/chat-images/a.png")).thenReturn(true);
        ChatApplicationService service = new ChatApplicationService(
            mock(AgentOrchestrator.class),
            mock(ChatRabbitProducer.class),
            memoryManager,
            storageService
        );

        Map<String, String> result = service.deleteSession("session-1");

        verify(memoryManager).getFullContext("session-1");
        verify(memoryManager).clearSession("session-1");
        verify(storageService, never()).deleteObject("https://example.com/not-managed.png");
        verify(storageService).deleteObject("oss://my-dev-oss/chat-images/a.png");
        assertThat(result).containsEntry("status", "deleted");
        assertThat(result).containsEntry("sessionId", "session-1");
    }

    @Test
    void deleteSessionStillSucceedsWhenAttachmentDeletionFails() throws IOException {
        MemoryManager memoryManager = mock(MemoryManager.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        MessageAttachment deleted = MessageAttachment.image(
            "oss://my-dev-oss/chat-images/a.png",
            "https://signed.example.com/a.png",
            "image/png",
            "a.png",
            2L
        );
        when(memoryManager.getFullContext("session-2")).thenReturn(List.of(
            new AgentMessage("user", "你好", Instant.now(), null, List.of(deleted))
        ));
        when(storageService.supports("oss://my-dev-oss/chat-images/a.png")).thenReturn(true);
        doThrow(new IOException("boom")).when(storageService).deleteObject("oss://my-dev-oss/chat-images/a.png");
        ChatApplicationService service = new ChatApplicationService(
            mock(AgentOrchestrator.class),
            mock(ChatRabbitProducer.class),
            memoryManager,
            storageService
        );

        Map<String, String> result = service.deleteSession("session-2");

        verify(memoryManager).clearSession("session-2");
        verify(storageService).deleteObject("oss://my-dev-oss/chat-images/a.png");
        assertThat(result).containsEntry("status", "deleted");
        assertThat(result).containsEntry("sessionId", "session-2");
    }

    @Test
    void blankSessionIdIsRejectedBeforeAnySideEffects() {
        ChatApplicationService service = new ChatApplicationService(
            mock(AgentOrchestrator.class),
            mock(ChatRabbitProducer.class),
            mock(MemoryManager.class),
            mock(ObjectStorageService.class)
        );

        assertThatThrownBy(() -> service.deleteSession(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sessionId不能为空");
    }

    @Test
    void deleteSessionSkipsAttachmentLookupWhenHistoryIsEmpty() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        when(memoryManager.getFullContext(anyString())).thenReturn(List.of());
        ChatApplicationService service = new ChatApplicationService(
            mock(AgentOrchestrator.class),
            mock(ChatRabbitProducer.class),
            memoryManager,
            storageService
        );

        Map<String, String> result = service.deleteSession("session-3");

        verify(memoryManager).clearSession("session-3");
        verifyNoInteractions(storageService);
        assertThat(result).containsEntry("status", "deleted");
    }
}
