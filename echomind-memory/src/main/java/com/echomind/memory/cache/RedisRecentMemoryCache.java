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
 * <p>每个会话一个 List，按消息数和字符数双重裁剪。Redis 永远只是热上下文缓存，
 * 不会保存完整会话历史。</p>
 */
@Slf4j
public class RedisRecentMemoryCache implements RecentMemoryCache {

    private static final String KEY_PREFIX = "echomind:memory:recent:";
    private static final String CHAR_KEY_PREFIX = "echomind:memory:recent-chars:";
    private static final String TOTAL_CHARS_KEY_PREFIX = "echomind:memory:recent-total-chars:";

    private static final DefaultRedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>("""
        redis.call('RPUSH', KEYS[1], ARGV[1])
        redis.call('RPUSH', KEYS[2], ARGV[2])
        redis.call('INCRBY', KEYS[3], ARGV[2])
        local maxMessages = tonumber(ARGV[3])
        local maxChars = tonumber(ARGV[4])
        local ttl = ARGV[5]
        while redis.call('LLEN', KEYS[1]) > maxMessages do
            local removedChars = tonumber(redis.call('LPOP', KEYS[2]) or '0')
            redis.call('LPOP', KEYS[1])
            redis.call('DECRBY', KEYS[3], removedChars)
        end
        while tonumber(redis.call('GET', KEYS[3]) or '0') > maxChars and redis.call('LLEN', KEYS[1]) > 1 do
            local removedChars = tonumber(redis.call('LPOP', KEYS[2]) or '0')
            redis.call('LPOP', KEYS[1])
            redis.call('DECRBY', KEYS[3], removedChars)
        end
        if ttl ~= '0' then
            redis.call('EXPIRE', KEYS[1], ttl)
            redis.call('EXPIRE', KEYS[2], ttl)
            redis.call('EXPIRE', KEYS[3], ttl)
        end
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisKeyScanner keyScanner;
    private final ObjectMapper mapper;
    private final int maxMessages;
    private final int maxChars;
    private final int maxMessageChars;
    private final long ttlSeconds;

    public RedisRecentMemoryCache(RedisConnectionFactory connectionFactory, int maxMessages, long ttlSeconds) {
        this(connectionFactory, maxMessages, 12000, 1500, ttlSeconds);
    }

    public RedisRecentMemoryCache(RedisConnectionFactory connectionFactory, int maxMessages,
                                  int maxChars, int maxMessageChars, long ttlSeconds) {
        this.redis = new StringRedisTemplate(connectionFactory);
        this.redis.afterPropertiesSet();
        this.keyScanner = new RedisKeyScanner(redis);
        this.mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.maxMessages = Math.max(1, maxMessages);
        this.maxChars = Math.max(200, maxChars);
        this.maxMessageChars = Math.max(100, maxMessageChars);
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void append(String sessionId, AgentMessage message) {
        String key = key(sessionId);
        try {
            AgentMessage cachedMessage = truncateMessage(message);
            String json = mapper.writeValueAsString(cachedMessage);
            redis.execute(APPEND_SCRIPT,
                List.of(key, charKey(sessionId), totalCharsKey(sessionId)),
                json,
                String.valueOf(messageChars(cachedMessage)),
                String.valueOf(maxMessages),
                String.valueOf(maxChars),
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
            List<AgentMessage> parsed = rows.stream()
                .map(this::fromJson)
                .map(this::truncateMessage)
                .filter(Objects::nonNull)
                .toList();
            return trimToCharBudget(parsed);
        } catch (Exception e) {
            log.warn("Failed to read recent memory for session {}: {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void clear(String sessionId) {
        redis.delete(List.of(key(sessionId), charKey(sessionId), totalCharsKey(sessionId)));
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

    private String charKey(String sessionId) {
        return CHAR_KEY_PREFIX + sessionId;
    }

    private String totalCharsKey(String sessionId) {
        return TOTAL_CHARS_KEY_PREFIX + sessionId;
    }

    private AgentMessage truncateMessage(AgentMessage message) {
        if (message == null || message.content() == null || message.content().length() <= maxMessageChars) {
            return message;
        }
        String content = message.content();
        String truncated = content.substring(0, Math.max(0, maxMessageChars - 3)) + "...";
        return new AgentMessage(message.role(), truncated, message.timestamp(), message.metadata(), message.attachments());
    }

    private int messageChars(AgentMessage message) {
        if (message == null || message.content() == null) {
            return 0;
        }
        return message.content().length();
    }

    private List<AgentMessage> trimToCharBudget(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        int total = 0;
        int start = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            int chars = messageChars(messages.get(i));
            if (start < messages.size() && total + chars > maxChars) {
                break;
            }
            total += chars;
            start = i;
        }
        return messages.subList(start, messages.size());
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
