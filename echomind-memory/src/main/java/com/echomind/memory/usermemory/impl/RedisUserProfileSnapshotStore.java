package com.echomind.memory.usermemory.impl;

import com.echomind.memory.usermemory.UserProfileSnapshot;
import com.echomind.memory.usermemory.UserProfileSnapshotStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Redis 用户画像快照存储。 */
@Slf4j
public class RedisUserProfileSnapshotStore implements UserProfileSnapshotStore {

    private final StringRedisTemplate redis;
    private final String keyPrefix;

    public RedisUserProfileSnapshotStore(RedisConnectionFactory connectionFactory, String keyPrefix) {
        this.redis = new StringRedisTemplate(connectionFactory);
        this.redis.afterPropertiesSet();
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank()
            ? "echomind:user-profile:snapshot:"
            : keyPrefix;
    }

    @Override
    public Optional<UserProfileSnapshot> get(String userId) {
        String owner = normalizeUserId(userId);
        try {
            Map<Object, Object> row = redis.opsForHash().entries(key(owner));
            if (row == null || row.isEmpty()) {
                return Optional.empty();
            }
            String content = text(row.get("content"));
            if (content == null || content.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new UserProfileSnapshot(
                owner,
                content,
                parseInt(row.get("version")),
                parseInstant(row.get("updatedAt"))
            ));
        } catch (Exception e) {
            log.warn("Failed to read user profile snapshot userId={}: {}", owner, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(UserProfileSnapshot snapshot) {
        if (snapshot == null || snapshot.content() == null || snapshot.content().isBlank()) {
            return;
        }
        String owner = normalizeUserId(snapshot.userId());
        Instant updatedAt = snapshot.updatedAt() == null ? Instant.now() : snapshot.updatedAt();
        try {
            redis.opsForHash().putAll(key(owner), Map.of(
                "userId", owner,
                "content", snapshot.content(),
                "version", String.valueOf(Math.max(1, snapshot.version())),
                "updatedAt", updatedAt.toString()
            ));
        } catch (Exception e) {
            log.warn("Failed to save user profile snapshot userId={}: {}", owner, e.getMessage());
        }
    }

    private String key(String userId) {
        return keyPrefix + userId;
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int parseInt(Object value) {
        try {
            return Integer.parseInt(text(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private Instant parseInstant(Object value) {
        try {
            String text = text(value);
            return text == null || text.isBlank() ? null : Instant.parse(text);
        } catch (Exception e) {
            return null;
        }
    }
}
