package com.echomind.memory;

import com.echomind.common.model.AgentMessage;
import com.echomind.memory.cache.InMemoryRecentMemoryCache;
import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.embedding.MemoryEmbeddingService;
import com.echomind.memory.embedding.MysqlMemoryVectorStore;
import com.echomind.memory.persistence.ChatMessageRepository;
import com.echomind.memory.persistence.ChatSessionRepository;
import com.echomind.memory.persistence.MemoryEmbeddingRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆主链路测试。
 *
 * <p>验证 MySQL 是完整历史来源，近期上下文可以从缓存/数据库回源，
 * 向量命中会作为 system 片段注入提示词。</p>
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class MemoryManagerTest {

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private MemoryEmbeddingRepository embeddingRepository;

    @Autowired
    private MemoryManager memoryManager;

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
        assertThat(sessionRepository.findById("session-1"))
            .get()
            .extracting(session -> session.getAgentId())
            .isEqualTo("agent-1");
    }

    @Test
    void promptContextUsesSummaryRelevantHistoryAndRecentMessages() {
        for (int i = 1; i <= 7; i++) {
            memoryManager.addMessage("session-2", "agent-1", AgentMessage.user("偏好-" + i + "：我喜欢喝拿铁"));
        }

        List<AgentMessage> context = memoryManager.getPromptContext("session-2", "我喜欢喝什么");

        assertThat(context).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("system");
            assertThat(message.content()).contains("会话摘要");
        });
        assertThat(context).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("system");
            assertThat(message.content()).contains("相关历史");
        });
        assertThat(context)
            .filteredOn(message -> "user".equals(message.role()))
            .extracting(AgentMessage::content)
            .containsExactly("偏好-6：我喜欢喝拿铁", "偏好-7：我喜欢喝拿铁");
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
        EmbeddingClient embeddingClient() {
            return text -> Optional.of(vectorFor(text));
        }

        @org.springframework.context.annotation.Bean
        MemoryEmbeddingService memoryEmbeddingService(MemoryEmbeddingRepository repository,
                                                      EmbeddingClient embeddingClient,
                                                      ObjectMapper mapper) {
            return new MemoryEmbeddingService(
                embeddingClient,
                new MysqlMemoryVectorStore(repository, mapper),
                true
            );
        }

        @org.springframework.context.annotation.Bean
        MemorySummaryService memorySummaryService() {
            return new MemorySummaryService(2, 1000);
        }

        @org.springframework.context.annotation.Bean
        MemoryManager memoryManager(PersistentChatMemoryStore store,
                                    MemoryEmbeddingService embeddingService,
                                    MemorySummaryService summaryService) {
            return new MemoryManager(
                new WindowConfig(2),
                store,
                new InMemoryRecentMemoryCache(2),
                embeddingService,
                summaryService,
                2,
                3
            );
        }

        private static double[] vectorFor(String text) {
            String value = text == null ? "" : text;
            return new double[] {
                value.contains("拿铁") || value.contains("喜欢喝") ? 1.0 : 0.1,
                value.length() % 7,
                1.0
            };
        }
    }
}
