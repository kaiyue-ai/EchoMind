package com.echomind.memory;

import com.echomind.common.model.AgentMessage;
import com.echomind.memory.cache.InMemoryRecentMemoryCache;
import com.echomind.memory.persistence.ChatMessageRepository;
import com.echomind.memory.persistence.ChatSessionId;
import com.echomind.memory.persistence.ChatSessionRepository;
import com.echomind.memory.persistence.PersistentChatMemoryStore;
import com.echomind.memory.shortterm.WindowConfig;
import com.echomind.memory.summary.MemorySummaryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆主链路测试。
 *
 * <p>验证 MySQL 是前端完整历史来源，LLM prompt 只使用近期缓存。</p>
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class MemoryManagerTest {

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private MemoryManager memoryManager;

    @Autowired
    private PersistentChatMemoryStore persistentChatMemoryStore;

    @Test
    void savesFullHistoryToMysqlAndKeepsOnlyRecentMessagesInCache() {
        memoryManager.addMessage("session-1", "agent-1", AgentMessage.user("第一条"));
        memoryManager.addMessage("session-1", "agent-1", AgentMessage.assistant("第二条"));
        memoryManager.addMessage("session-1", "agent-1", AgentMessage.user("第三条"));

        assertThat(memoryManager.getFullContext("session-1"))
            .extracting(AgentMessage::content)
            .containsExactly("第一条", "第二条", "第三条");
        assertThat(memoryManager.getRecentMessages("session-1"))
            .extracting(AgentMessage::content)
            .containsExactly("第二条", "第三条");
        assertThat(sessionRepository.findById(new ChatSessionId("default", "session-1")))
            .get()
            .extracting(session -> session.getAgentId())
            .isEqualTo("agent-1");
    }

    @Test
    void isolatesMysqlAndRecentCacheByUserAndSession() {
        memoryManager.addMessage("user-a", "shared-session", "agent-1", AgentMessage.user("A 的消息"));
        memoryManager.addMessage("user-b", "shared-session", "agent-1", AgentMessage.user("B 的消息"));

        assertThat(memoryManager.getFullContext("user-a", "shared-session"))
            .extracting(AgentMessage::content)
            .containsExactly("A 的消息");
        assertThat(memoryManager.getFullContext("user-b", "shared-session"))
            .extracting(AgentMessage::content)
            .containsExactly("B 的消息");
        assertThat(memoryManager.listSessions("user-a"))
            .extracting(com.echomind.common.model.SessionSummary::sessionId)
            .containsExactly("shared-session");
        assertThat(memoryManager.listSessions("user-b"))
            .extracting(com.echomind.common.model.SessionSummary::sessionId)
            .containsExactly("shared-session");
        assertThat(sessionRepository.findById(new ChatSessionId("user-a", "shared-session"))).isPresent();
        assertThat(sessionRepository.findById(new ChatSessionId("user-b", "shared-session"))).isPresent();
    }

    @Test
    void promptContextUsesOnlyRecentCacheMessages() {
        for (int i = 1; i <= 7; i++) {
            memoryManager.addMessage("session-2", "agent-1", AgentMessage.user("偏好-" + i + "：我喜欢喝拿铁"));
        }

        List<AgentMessage> context = memoryManager.getPromptContext("session-2");

        assertThat(context)
            .extracting(AgentMessage::content)
            .containsExactly("偏好-6：我喜欢喝拿铁", "偏好-7：我喜欢喝拿铁");
    }

    @Test
    void promptContextDoesNotBackfillFromMysqlWhenRecentCacheIsEmpty() {
        memoryManager.addMessage("session-3", "agent-1", AgentMessage.user("不会进入新缓存"));
        MemoryManager freshManager = new MemoryManager(
            new WindowConfig(2),
            persistentChatMemoryStore,
            new InMemoryRecentMemoryCache(2),
            new MemorySummaryService(2, 1000),
            3
        );

        assertThat(freshManager.getPromptContext("session-3")).isEmpty();
        assertThat(freshManager.getFullContext("session-3"))
            .extracting(AgentMessage::content)
            .containsExactly("不会进入新缓存");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackages = "com.echomind.memory.persistence")
    @EntityScan(basePackages = "com.echomind.memory.persistence")
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        @org.springframework.context.annotation.Bean
        PersistentChatMemoryStore persistentChatMemoryStore(ChatSessionRepository sessionRepository,
                                                            ChatMessageRepository messageRepository,
                                                            ObjectMapper mapper) {
            return new PersistentChatMemoryStore(sessionRepository, messageRepository, mapper);
        }

        @org.springframework.context.annotation.Bean
        MemorySummaryService memorySummaryService() {
            return new MemorySummaryService(2, 1000);
        }

        @org.springframework.context.annotation.Bean
        MemoryManager memoryManager(PersistentChatMemoryStore store,
                                    MemorySummaryService summaryService) {
            return new MemoryManager(
                new WindowConfig(2),
                store,
                new InMemoryRecentMemoryCache(2),
                summaryService,
                3
            );
        }
    }
}
