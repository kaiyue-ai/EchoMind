package com.echomind.memory.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisStackMemoryVectorStoreTest {

    @Test
    void fallsBackToMysqlWhenRedisSearchFails() {
        RecordingMysqlStore fallback = new RecordingMysqlStore();
        fallback.hits = List.of(new MemorySearchHit(7L, "user", "我喜欢拿铁", 0.9));
        RedisStackMemoryVectorStore store = new RedisStackMemoryVectorStore(
            mock(RedisConnectionFactory.class),
            fallback,
            "idx:test",
            "test:vector:"
        );

        List<MemorySearchHit> hits = store.search("session-1", new double[] {1, 0, 1}, 3);

        assertThat(hits).extracting(MemorySearchHit::content).containsExactly("我喜欢拿铁");
        assertThat(fallback.searchCalled).isTrue();
    }

    @Test
    void alwaysWritesMysqlBackupBeforeTryingRedis() {
        RecordingMysqlStore fallback = new RecordingMysqlStore();
        RedisStackMemoryVectorStore store = new RedisStackMemoryVectorStore(
            mock(RedisConnectionFactory.class),
            fallback,
            "idx:test",
            "test:vector:"
        );

        store.save(new MemoryVectorRecord("session-1", 1L, "user", "hello", new double[] {0.1, 0.2}));

        assertThat(fallback.saved).isNotNull();
        assertThat(fallback.saved.messageId()).isEqualTo(1L);
    }

    private static class RecordingMysqlStore extends MysqlMemoryVectorStore {
        private MemoryVectorRecord saved;
        private boolean searchCalled;
        private List<MemorySearchHit> hits = List.of();

        private RecordingMysqlStore() {
            super(null, null);
        }

        @Override
        public void save(MemoryVectorRecord record) {
            this.saved = record;
        }

        @Override
        public List<MemorySearchHit> search(String sessionId, double[] queryVector, int topK) {
            this.searchCalled = true;
            return hits;
        }

        @Override
        public void deleteSession(String sessionId) {
        }
    }
}
