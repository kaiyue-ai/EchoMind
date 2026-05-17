package com.echomind.boot.autoconfigure;

import com.echomind.boot.properties.EchoMindProperties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryShardBindingTest {

    @Test
    void declaresOneDurableQueueAndBindingPerShard() {
        EchoMindProperties props = new EchoMindProperties();
        props.getMemory().setPersistQueueName("chat.memory.persist");
        props.getMemory().setPersistExchangeName("chat.memory.exchange");
        props.getMemory().setPersistShards(3);
        EchoMindAutoConfiguration config = new EchoMindAutoConfiguration();

        DirectExchange exchange = config.chatMemoryPersistExchange(props);
        List<Declarable> declarables = config.chatMemoryPersistShardBindings(props, exchange)
            .getDeclarables()
            .stream()
            .toList();

        assertThat(exchange.getName()).isEqualTo("chat.memory.exchange");
        assertThat(declarables).filteredOn(Queue.class::isInstance)
            .extracting(d -> ((Queue) d).getName())
            .containsExactly("chat.memory.persist.shard.0", "chat.memory.persist.shard.1", "chat.memory.persist.shard.2");
        assertThat(declarables).filteredOn(Binding.class::isInstance)
            .extracting(d -> ((Binding) d).getRoutingKey())
            .containsExactly("shard.0", "shard.1", "shard.2");
    }

    @Test
    void normalizesInvalidShardCountToOneShard() {
        EchoMindProperties props = new EchoMindProperties();
        props.getMemory().setPersistQueueName("chat.memory.persist");
        props.getMemory().setPersistShards(0);
        EchoMindAutoConfiguration config = new EchoMindAutoConfiguration();

        DirectExchange exchange = config.chatMemoryPersistExchange(props);
        List<Declarable> declarables = config.chatMemoryPersistShardBindings(props, exchange)
            .getDeclarables()
            .stream()
            .toList();

        assertThat(declarables).filteredOn(Queue.class::isInstance)
            .extracting(d -> ((Queue) d).getName())
            .containsExactly("chat.memory.persist.shard.0");
    }
}
