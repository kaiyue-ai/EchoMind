package com.echomind.console.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MessageAttachment;
import com.echomind.common.model.SessionSummary;
import com.echomind.memory.MemoryManager;
import com.echomind.skill.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
    void listSessionsStripsLeakedHiddenMemoryDecisionFromPreview() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryApplicationService service = new MemoryApplicationService(memoryManager, mock(ObjectStorageService.class));
        when(memoryManager.listSessions("default")).thenReturn(List.of(
            new SessionSummary("session-1", 1,
                "正文<ECHOMIMD_MEMORY_DECISION>{\"rememberFacts\":false,\"refreshProfile\":false,\"reason\":\"闲聊\"}</ECHOMIMD_MEMORY_DECISION>",
                Instant.now())
        ));

        List<SessionSummary> result = service.listSessions();

        assertThat(result.get(0).lastMessage()).isEqualTo("正文");
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
    void getChatHistoryStripsLeakedHiddenMemoryDecision() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryApplicationService service = new MemoryApplicationService(memoryManager, mock(ObjectStorageService.class));
        when(memoryManager.getFullContext("default", "session-1")).thenReturn(List.of(
            new AgentMessage("assistant",
                "正文<ECHOMIMD_MEMORY_DECISION>{\"rememberFacts\":false,\"refreshProfile\":false,\"reason\":\"闲聊\"}</ECHOMIMD_MEMORY_DECISION>",
                Instant.now(), null)
        ));

        List<AgentMessage> result = service.getChatHistory("session-1");

        assertThat(result.get(0).content()).isEqualTo("正文");
    }

    @Test
    void getChatHistoryHidesToolMessagesFromDisplayHistory() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryApplicationService service = new MemoryApplicationService(memoryManager, mock(ObjectStorageService.class));
        when(memoryManager.getFullContext("default", "session-1")).thenReturn(List.of(
            AgentMessage.user("查询一下今天新闻"),
            AgentMessage.tool("open_web_search", "open_web_search"),
            AgentMessage.assistant("今天新闻摘要")
        ));

        List<AgentMessage> result = service.getChatHistory("session-1");

        assertThat(result)
            .extracting(AgentMessage::role)
            .containsExactly("user", "assistant");
        assertThat(result)
            .extracting(AgentMessage::content)
            .containsExactly("查询一下今天新闻", "今天新闻摘要");
    }

    @Test
    void blankSessionIdIsRejected() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryApplicationService service = new MemoryApplicationService(memoryManager, mock(ObjectStorageService.class));

        assertThatThrownBy(() -> service.getChatHistory(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sessionId不能为空");

        verifyNoInteractions(memoryManager);
    }
}
