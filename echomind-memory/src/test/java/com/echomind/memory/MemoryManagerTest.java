package com.echomind.memory;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.mybatis.MybatisPlusMetaObjectHandler;
import com.echomind.memory.cache.InMemoryRecentMemoryCache;
import com.echomind.memory.persistence.mapper.ChatMessageMapper;
import com.echomind.memory.persistence.mapper.ChatSessionMapper;
import com.echomind.memory.persistence.PersistentChatMemoryStore;
import com.echomind.memory.shortterm.WindowConfig;
import com.echomind.memory.summary.MemorySummaryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆主链路测试。
 *
 * <p>验证 MySQL 是前端完整历史来源，LLM prompt 只使用近期缓存。</p>
 */
@SpringBootTest(classes = MemoryManagerTest.TestConfig.class)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:memory-manager-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:memory-manager-schema.sql",
    "mybatis-plus.configuration.map-underscore-to-camel-case=true"
})
class MemoryManagerTest {

    @Autowired
    private ChatSessionMapper sessionMapper;

    @Autowired
    private ChatMessageMapper messageMapper;

    @Autowired
    private MemoryManager memoryManager;

    @Autowired
    private PersistentChatMemoryStore persistentChatMemoryStore;

    @Test
    void savesFullHistoryToMysqlAndKeepsOnlyRecentMessagesInCache() {
        memoryManager.addMessage("default", "session-1", "agent-1", AgentMessage.user("第一条"));
        memoryManager.addMessage("default", "session-1", "agent-1", AgentMessage.assistant("第二条"));
        memoryManager.addMessage("default", "session-1", "agent-1", AgentMessage.user("第三条"));

        assertThat(memoryManager.getFullContext("default", "session-1"))
            .extracting(AgentMessage::content)
            .containsExactly("第一条", "第二条", "第三条");
        assertThat(sessionMapper.selectByUserIdAndSessionId("default", "session-1"))
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
        assertThat(memoryManager.getPromptContext("user-a", "shared-session"))
            .extracting(AgentMessage::content)
            .containsExactly("A 的消息");
        assertThat(memoryManager.getPromptContext("user-b", "shared-session"))
            .extracting(AgentMessage::content)
            .containsExactly("B 的消息");
        assertThat(memoryManager.listSessions("user-a"))
            .extracting(com.echomind.common.model.SessionSummary::sessionId)
            .containsExactly("shared-session");
        assertThat(memoryManager.listSessions("user-b"))
            .extracting(com.echomind.common.model.SessionSummary::sessionId)
            .containsExactly("shared-session");
        assertThat(sessionMapper.selectByUserIdAndSessionId("user-a", "shared-session")).isPresent();
        assertThat(sessionMapper.selectByUserIdAndSessionId("user-b", "shared-session")).isPresent();
    }

    @Test
    void promptContextUsesOnlyRecentCacheMessages() {
        for (int i = 1; i <= 7; i++) {
            memoryManager.addMessage("default", "session-2", "agent-1", AgentMessage.user("偏好-" + i + "：我喜欢喝拿铁"));
        }

        List<AgentMessage> context = memoryManager.getPromptContext("session-2");

        assertThat(context)
            .extracting(AgentMessage::content)
            .containsExactly("偏好-6：我喜欢喝拿铁", "偏好-7：我喜欢喝拿铁");
    }

    @Test
    void promptContextDoesNotBackfillFromMysqlWhenRecentCacheIsEmpty() {
        memoryManager.addMessage("default", "session-3", "agent-1", AgentMessage.user("不会进入新缓存"));
        MemoryManager freshManager = new MemoryManager(
            new WindowConfig(2),
            persistentChatMemoryStore,
            new InMemoryRecentMemoryCache(2),
            new MemorySummaryService(2, 1000),
            3
        );

        assertThat(freshManager.getPromptContext("session-3")).isEmpty();
        assertThat(freshManager.getFullContext("default", "session-3"))
            .extracting(AgentMessage::content)
            .containsExactly("不会进入新缓存");
    }

    @Test
    void sessionPreviewUsesLatestNonToolMessage() {
        memoryManager.addMessage("default", "session-tool-preview", "agent-1", AgentMessage.user("查今天新闻"));
        memoryManager.addMessage("default", "session-tool-preview", "agent-1", AgentMessage.assistant("正在整理"));
        memoryManager.addMessage("default", "session-tool-preview", "agent-1",
            AgentMessage.tool("open_web_search", "open_web_search"));

        assertThat(memoryManager.listSessions("default"))
            .filteredOn(summary -> "session-tool-preview".equals(summary.sessionId()))
            .singleElement()
            .extracting(com.echomind.common.model.SessionSummary::lastMessage)
            .isEqualTo("正在整理");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan(basePackages = "com.echomind.memory.persistence.mapper")
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        MybatisPlusMetaObjectHandler mybatisPlusMetaObjectHandler() {
            return new MybatisPlusMetaObjectHandler();
        }

        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        @org.springframework.context.annotation.Bean
        PersistentChatMemoryStore persistentChatMemoryStore(ChatSessionMapper sessionMapper,
                                                            ChatMessageMapper messageMapper,
                                                            ObjectMapper mapper) {
            return new PersistentChatMemoryStore(sessionMapper, messageMapper, mapper);
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
