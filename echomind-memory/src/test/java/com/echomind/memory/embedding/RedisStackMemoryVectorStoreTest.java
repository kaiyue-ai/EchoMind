package com.echomind.memory.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class RedisStackMemoryVectorStoreTest {

    @Test
    void returnsEmptyWhenRedisSearchFailsWithoutMysqlFallback() {
        RedisStackMemoryVectorStore store = new RedisStackMemoryVectorStore(
            mock(RedisConnectionFactory.class),
            "idx:test",
            "test:vector:"
        );

        List<MemorySearchHit> hits = store.search("session-1", new double[] {1, 0, 1}, 3);

        assertThat(hits).isEmpty();
    }

    @Test
    void saveFailureDoesNotWriteMysqlBackup() {
        RedisStackMemoryVectorStore store = new RedisStackMemoryVectorStore(
            mock(RedisConnectionFactory.class),
            "idx:test",
            "test:vector:"
        );

        assertThatCode(() -> store.save(
            new MemoryVectorRecord("session-1", 1L, "user", "hello", new double[] {0.1, 0.2})
        )).doesNotThrowAnyException();
    }
}
