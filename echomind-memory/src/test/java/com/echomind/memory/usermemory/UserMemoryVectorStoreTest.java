package com.echomind.memory.usermemory;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UserMemoryVectorStoreTest {

    @Test
    void searchRejectsBlankInputsBeforeTouchingRedis() {
        UserMemoryVectorStore store = new UserMemoryVectorStore(
            mock(RedisConnectionFactory.class),
            "idx:test:user-memory",
            "test:user-memory:"
        );

        List<UserMemoryHit> hits = store.search("", new double[] {0.1}, 5, 0.3);

        assertThat(hits).isEmpty();
    }
}
