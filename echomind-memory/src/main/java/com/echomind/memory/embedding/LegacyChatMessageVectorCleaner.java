package com.echomind.memory.embedding;

import com.echomind.memory.redis.RedisKeyScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static com.echomind.memory.embedding.RedisStackVectorStoreSupport.bytes;
import static com.echomind.memory.embedding.RedisStackVectorStoreSupport.asList;
import static com.echomind.memory.embedding.RedisStackVectorStoreSupport.executeArrayCommand;
import static com.echomind.memory.embedding.RedisStackVectorStoreSupport.executeStatusCommand;
import static com.echomind.memory.embedding.RedisStackVectorStoreSupport.text;

/** 清理已废弃的普通聊天消息向量索引，避免旧数据被误认为仍在使用。 */
@Slf4j
public class LegacyChatMessageVectorCleaner {

    private final RedisConnectionFactory connectionFactory;
    private final RedisKeyScanner keyScanner;
    private final String indexName;
    private final String keyPrefix;

    public LegacyChatMessageVectorCleaner(RedisConnectionFactory connectionFactory,
                                          String indexName,
                                          String keyPrefix) {
        this.connectionFactory = connectionFactory;
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        this.keyScanner = new RedisKeyScanner(redis);
        this.indexName = blankToDefault(indexName, "idx:echomind:memory:vectors");
        this.keyPrefix = blankToDefault(keyPrefix, "echomind:memory:vector:");
    }

    public void clean() {
        dropIndex();
        long deleted = keyScanner.deleteByPattern(keyPrefix + "*");
        if (deleted > 0) {
            log.info("Deleted {} legacy chat message vector keys with prefix {}", deleted, keyPrefix);
        }
    }

    private void dropIndex() {
        if (!indexExists()) {
            return;
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
            executeStatusCommand(connection, "FT.DROPINDEX", bytes(indexName));
            log.info("Dropped legacy chat message vector index {}", indexName);
        } catch (Exception e) {
            log.warn("Failed to drop legacy chat message vector index {}: {}", indexName, e.getMessage());
        }
    }

    private boolean indexExists() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Object raw = executeArrayCommand(connection, "FT._LIST");
            List<?> indexes = asList(raw);
            for (Object index : indexes) {
                if (indexName.equals(text(index))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("Failed to list Redis Stack indexes before legacy cleanup: {}", e.getMessage());
            return false;
        }
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
