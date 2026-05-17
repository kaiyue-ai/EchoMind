package com.echomind.memory.cache;

import com.echomind.common.model.AgentMessage;
import com.echomind.memory.redis.RedisKeyScanner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis 近期记忆缓存。
 *
 * <p>每个会话一个 List，只保留最近 N 条。写入时使用 RPUSH + LTRIM，
 * 所以 Redis 永远只是热上下文缓存，不会保存完整会话历史。</p>
 */
@Slf4j
public class RedisRecentMemoryCache implements RecentMemoryCache {

    private static final String KEY_PREFIX = "echomind:memory:recent:";

    private static final DefaultRedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>("""
        redis.call('RPUSH', KEYS[1], ARGV[1])
        redis.call('LTRIM', KEYS[1], -ARGV[2], -1)
        if ARGV[3] ~= '0' then
            redis.call('EXPIRE', KEYS[1], ARGV[3])
        end
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisKeyScanner keyScanner;
    private final ObjectMapper mapper;
    private final int maxMessages;
    private final long ttlSeconds;

    public RedisRecentMemoryCache(RedisConnectionFactory connectionFactory, int maxMessages, long ttlSeconds) {
        this.redis = new StringRedisTemplate(connectionFactory);
        this.redis.afterPropertiesSet();
        this.keyScanner = new RedisKeyScanner(redis);
        this.mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.maxMessages = Math.max(1, maxMessages);
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void append(String sessionId, AgentMessage message) {
        String key = key(sessionId);
        try {
            String json = mapper.writeValueAsString(message);
            redis.execute(APPEND_SCRIPT,
                List.of(key),
                json,
                String.valueOf(maxMessages),
                String.valueOf(ttlSeconds));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize recent memory for session {}: {}", sessionId, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to append recent memory for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public List<AgentMessage> recent(String sessionId) {
        try {
            // 读路径也显式按窗口读取，避免旧脏数据或配置变化导致一次拉取过多历史。
            List<String> rows = redis.opsForList().range(key(sessionId), -maxMessages, -1);
            if (rows == null || rows.isEmpty()) {
                return Collections.emptyList();
            }
            return rows.stream()
                .map(this::fromJson)
                .filter(Objects::nonNull)
                .toList();
        } catch (Exception e) {
            log.warn("Failed to read recent memory for session {}: {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void clear(String sessionId) {
        redis.delete(key(sessionId));
    }

    @Override
    public Set<String> sessionIds() {
        try {
            Set<String> keys = keyScanner.scan(KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return Collections.emptySet();
            }
            return keys.stream()
                .map(key -> key.substring(KEY_PREFIX.length()))
                .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Failed to list recent memory keys: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    @Override
    public int size(String sessionId) {
        Long size = redis.opsForList().size(key(sessionId));
        return size == null ? 0 : size.intValue();
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private AgentMessage fromJson(String json) {
        try {
            return mapper.readValue(json, AgentMessage.class);
        } catch (Exception e) {
            log.warn("Failed to parse recent memory json: {}", e.getMessage());
            return null;
        }
    }
}
