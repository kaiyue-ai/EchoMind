package com.echomind.console.users;

import com.echomind.memory.redis.RedisKeyScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;

/** 清理客户端用户在 Redis 中的近期上下文、画像和长期事实向量。 */
@Slf4j
@Service
public class ClientUserRedisCleanupService {

    private static final String RECENT_MEMORY_PREFIX = "echomind:memory:recent:";
    private static final String PROFILE_PREFIX = "echomind:user-profile:snapshot:";
    private static final String USER_MEMORY_BUFFER_PREFIX = "echomind:user-memory:buffer:";
    private static final String USER_MEMORY_VECTOR_PREFIX = "user:memory:vector:";

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
            deleted += delete(USER_MEMORY_BUFFER_PREFIX + userId);
            deleted += deleteByPattern(RECENT_MEMORY_PREFIX + userId + ":*");
            deleted += deleteByPattern(USER_MEMORY_VECTOR_PREFIX + encodeId("user:" + userId) + ":*");
            for (String sessionId : sessionIds) {
                if (sessionId == null || sessionId.isBlank()) {
                    continue;
                }
                deleted += delete(RECENT_MEMORY_PREFIX + userId + ":" + sessionId);
                deleted += delete(RECENT_MEMORY_PREFIX + sessionId);
                deleted += deleteByPattern(USER_MEMORY_VECTOR_PREFIX + encodeId(sessionId) + ":*");
                deleted += deleteByPattern(USER_MEMORY_VECTOR_PREFIX + encodeId(userId + ":" + sessionId) + ":*");
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

    private String encodeId(String id) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }
}
