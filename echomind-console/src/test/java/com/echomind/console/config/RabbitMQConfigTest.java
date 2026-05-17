package com.echomind.console.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQConfigTest {

    @Test
    void buildsChatMemoryPersistShardQueueNames() {
        RabbitMQConfig config = new RabbitMQConfig();

        assertThat(config.chatMemoryPersistQueueNames("chat.memory.persist", 4))
            .containsExactly(
                "chat.memory.persist.shard.0",
                "chat.memory.persist.shard.1",
                "chat.memory.persist.shard.2",
                "chat.memory.persist.shard.3"
            );
    }

    @Test
    void normalizesInvalidShardCountToOneQueue() {
        RabbitMQConfig config = new RabbitMQConfig();

        assertThat(config.chatMemoryPersistQueueNames("chat.memory.persist", 0))
            .containsExactly("chat.memory.persist.shard.0");
    }
}
