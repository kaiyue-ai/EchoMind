package com.echomind.console.users;

import com.echomind.memory.cache.RedisKeyScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;

/** 清理客户端用户在 Redis 中的近期上下文和画像快照。 */
@Slf4j
@Service
public class ClientUserRedisCleanupService {

    private static final String RECENT_MEMORY_PREFIX = "echomind:memory:recent:";
    private static final String PROFILE_PREFIX = "echomind:user-profile:snapshot:";
    private static final String LEGACY_USER_MEMORY_BUFFER_PREFIX = "echomind:user-memory:buffer:";
    private static final String LEGACY_USER_MEMORY_BUFFER_META_PREFIX = "echomind:user-memory:buffer-meta:";
    private static final String LEGACY_USER_MEMORY_BUFFER_LOCK_PREFIX = "echomind:user-memory:buffer-lock:";

    private final StringRedisTemplate redis;
    private final RedisKeyScanner keyScanner;

    public ClientUserRedisCleanupService(ObjectProvider<RedisConnectionFactory> connectionFactoryProvider) {
        RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        if (connectionFactory == null) {
            this.redis = null;
            this.keyScanner = null;
            return;
        }
        this.redis = new StringRedisTemplate(connectionFactory);
        this.redis.afterPropertiesSet();
        this.keyScanner = new RedisKeyScanner(this.redis);
    }

    public long cleanup(String userId, Collection<String> sessionIds) {
        if (redis == null || userId == null || userId.isBlank()) {
            return 0;
        }
        long deleted = 0;
        try {
            deleted += delete(PROFILE_PREFIX + userId);
            // 旧版用户长期记忆曾使用 Redis 缓冲区，当前已改为主模型决策后即时异步处理。
            deleted += delete(LEGACY_USER_MEMORY_BUFFER_PREFIX + userId);
            deleted += delete(LEGACY_USER_MEMORY_BUFFER_META_PREFIX + userId);
            deleted += delete(LEGACY_USER_MEMORY_BUFFER_LOCK_PREFIX + userId);
            deleted += deleteByPattern(RECENT_MEMORY_PREFIX + userId + ":*");
            for (String sessionId : sessionIds) {
                if (sessionId == null || sessionId.isBlank()) {
                    continue;
                }
                deleted += delete(RECENT_MEMORY_PREFIX + userId + ":" + sessionId);
                deleted += delete(RECENT_MEMORY_PREFIX + sessionId);
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup Redis data for client user {}: {}", userId, e.getMessage());
        }
        return deleted;
    }

    private long delete(String key) {
        Boolean deleted = redis.delete(key);
        return Boolean.TRUE.equals(deleted) ? 1 : 0;
    }

    private long deleteByPattern(String pattern) {
        return keyScanner == null ? 0 : keyScanner.deleteByPattern(pattern);
    }
}
