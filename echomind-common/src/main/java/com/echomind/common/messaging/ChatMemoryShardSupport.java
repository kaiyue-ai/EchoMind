package com.echomind.common.messaging;

import java.util.List;
import java.util.stream.IntStream;

/** 普通聊天记忆 RabbitMQ 分片命名和路由规则。 */
public final class ChatMemoryShardSupport {

    public static final int DEFAULT_SHARD_COUNT = 8;
    public static final String DEFAULT_EXCHANGE_NAME = "echomind.chat-memory.persist.exchange";

    private ChatMemoryShardSupport() {
    }

    /** 分片数至少为 1，避免配置写错导致发布端和消费端不可用。 */
    public static int normalizeShardCount(int shardCount) {
        return Math.max(1, shardCount);
    }

    /** 同一个 sessionId 永远落到同一个分片。 */
    public static int shardIndex(String sessionId, int shardCount) {
        return Math.floorMod(sessionId.hashCode(), normalizeShardCount(shardCount));
    }

    public static String routingKey(int shardIndex) {
        return "shard." + shardIndex;
    }

    public static String queueName(String baseQueueName, int shardIndex) {
        return baseQueueName + ".shard." + shardIndex;
    }

    public static List<String> queueNames(String baseQueueName, int shardCount) {
        int normalized = normalizeShardCount(shardCount);
        return IntStream.range(0, normalized)
            .mapToObj(i -> queueName(baseQueueName, i))
            .toList();
    }
}
