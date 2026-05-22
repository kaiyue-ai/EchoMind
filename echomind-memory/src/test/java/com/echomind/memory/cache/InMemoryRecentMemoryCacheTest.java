package com.echomind.memory.cache;

import com.echomind.common.model.AgentMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRecentMemoryCacheTest {

    @Test
    void trimsByTotalCharacterBudget() {
        InMemoryRecentMemoryCache cache = new InMemoryRecentMemoryCache(10, 210, 120);

        cache.append("session-1", AgentMessage.user("a".repeat(100)));
        cache.append("session-1", AgentMessage.assistant("b".repeat(100)));
        cache.append("session-1", AgentMessage.user("c".repeat(100)));

        assertThat(cache.recent("session-1"))
            .extracting(AgentMessage::content)
            .containsExactly("b".repeat(100), "c".repeat(100));
    }

    @Test
    void truncatesSingleOversizedMessageBeforeCaching() {
        InMemoryRecentMemoryCache cache = new InMemoryRecentMemoryCache(10, 300, 110);

        cache.append("session-1", AgentMessage.user("a".repeat(150)));

        assertThat(cache.recent("session-1"))
            .singleElement()
            .extracting(AgentMessage::content)
            .isEqualTo("a".repeat(107) + "...");
    }
}
