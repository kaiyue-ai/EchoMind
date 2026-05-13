package com.echomind.console.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.memory.MemoryManager;
import org.junit.jupiter.api.Test;

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
 * <p>重点确认记忆入口只按sessionId工作：Web层传入非法会话时不触碰MemoryManager，
 * 合法请求则直接委托给当前“一次对话一份记忆”的事实来源。</p>
 */
class MemoryApplicationServiceTest {

    @Test
    void getMemoryDelegatesToMemoryManager() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryApplicationService service = new MemoryApplicationService(memoryManager);
        AgentMessage message = new AgentMessage("user", "你好", Instant.now(), null);
        when(memoryManager.getFullContext("session-1")).thenReturn(List.of(message));

        List<AgentMessage> result = service.getMemory("session-1");

        assertThat(result).containsExactly(message);
    }

    @Test
    void clearMemoryValidatesSessionIdBeforeDeleting() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryApplicationService service = new MemoryApplicationService(memoryManager);

        Map<String, String> result = service.clearMemory("session-1");

        verify(memoryManager).clearSession("session-1");
        assertThat(result).containsEntry("status", "cleared");
        assertThat(result).containsEntry("sessionId", "session-1");
    }

    @Test
    void blankSessionIdIsRejected() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryApplicationService service = new MemoryApplicationService(memoryManager);

        assertThatThrownBy(() -> service.getMemory(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sessionId不能为空");

        verifyNoInteractions(memoryManager);
    }
}
