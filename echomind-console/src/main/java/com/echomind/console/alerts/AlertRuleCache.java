package com.echomind.console.alerts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Slf4j
@Component
public class AlertRuleCache {

    static final String ALL_RULES_KEY = "echomind:alert:rules:all";
    private static final String RULE_BY_TYPE_KEY_PREFIX = "echomind:alert:rules:type:";
    private static final String RELOAD_LOCK_KEY_PREFIX = "echomind:alert:rules:reload-lock:";
    private static final String NULL_SENTINEL = "__NULL__";

    private static final long NORMAL_TTL_SECONDS = 600;
    private static final long NORMAL_TTL_JITTER_SECONDS = 120;
    private static final long EMPTY_TTL_SECONDS = 30;
    private static final long EMPTY_TTL_JITTER_SECONDS = 10;
    private static final Duration RELOAD_LOCK_TTL = Duration.ofSeconds(5);
    private static final Duration RELOAD_WAIT = Duration.ofMillis(60);

    private static final TypeReference<List<AlertRuleEntity>> RULE_LIST_TYPE = new TypeReference<>() {
    };
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    @Autowired
    public AlertRuleCache(ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
                          ObjectProvider<ObjectMapper> mapperProvider) {
        this(createRedis(connectionFactoryProvider), mapperProvider.getIfAvailable(AlertRuleCache::defaultMapper));
    }

    AlertRuleCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper == null ? defaultMapper() : mapper.copy().findAndRegisterModules();
    }

    static AlertRuleCache disabled() {
        return new AlertRuleCache(null, defaultMapper());
    }

    public List<AlertRuleEntity> allRules(Supplier<List<AlertRuleEntity>> loader) {
        return getListOrLoad(ALL_RULES_KEY, loader);
    }

    public Optional<AlertRuleEntity> ruleByType(AlertType type, Supplier<Optional<AlertRuleEntity>> loader) {
        if (type == null) {
            return Optional.empty();
        }
        return getOneOrLoad(ruleByTypeKey(type), loader);
    }

    public void invalidateRules() {
        if (redis == null) {
            return;
        }
        try {
            redis.delete(cacheKeys());
        } catch (Exception e) {
            log.warn("Failed to invalidate alert rule cache: {}", e.getMessage());
        }
    }

    private List<AlertRuleEntity> getListOrLoad(String key, Supplier<List<AlertRuleEntity>> loader) {
        if (redis == null) {
            return loadList(loader);
        }
        CacheRead<List<AlertRuleEntity>> cached = readList(key);
        if (cached.hit()) {
            return cached.value();
        }
        if (!cached.redisAvailable()) {
            return loadList(loader);
        }

        ReloadLock lock = tryAcquireReloadLock(key);
        if (!lock.redisAvailable()) {
            return loadList(loader);
        }
        if (!lock.acquired()) {
            waitBriefly();
            cached = readList(key);
            if (cached.hit()) {
                return cached.value();
            }
            if (!cached.redisAvailable()) {
                return loadList(loader);
            }
            return loadAndCacheList(key, loader);
        }

        try {
            return loadAndCacheList(key, loader);
        } finally {
            releaseReloadLock(lock.key(), lock.value());
        }
    }

    private Optional<AlertRuleEntity> getOneOrLoad(String key, Supplier<Optional<AlertRuleEntity>> loader) {
        if (redis == null) {
            return loadOne(loader);
        }
        CacheRead<Optional<AlertRuleEntity>> cached = readOne(key);
        if (cached.hit()) {
            return cached.value();
        }
        if (!cached.redisAvailable()) {
            return loadOne(loader);
        }

        ReloadLock lock = tryAcquireReloadLock(key);
        if (!lock.redisAvailable()) {
            return loadOne(loader);
        }
        if (!lock.acquired()) {
            waitBriefly();
            cached = readOne(key);
            if (cached.hit()) {
                return cached.value();
            }
            if (!cached.redisAvailable()) {
                return loadOne(loader);
            }
            return loadAndCacheOne(key, loader);
        }

        try {
            return loadAndCacheOne(key, loader);
        } finally {
            releaseReloadLock(lock.key(), lock.value());
        }
    }

    private CacheRead<List<AlertRuleEntity>> readList(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return CacheRead.miss();
            }
            if (json.isBlank()) {
                redis.delete(key);
                return CacheRead.miss();
            }
            return CacheRead.hit(sanitize(mapper.readValue(json, RULE_LIST_TYPE)));
        } catch (Exception e) {
            log.warn("Failed to read alert rule cache {}: {}", key, e.getMessage());
            deleteBadKey(key);
            return CacheRead.redisUnavailable();
        }
    }

    private CacheRead<Optional<AlertRuleEntity>> readOne(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return CacheRead.miss();
            }
            if (NULL_SENTINEL.equals(json)) {
                return CacheRead.hit(Optional.empty());
            }
            if (json.isBlank()) {
                redis.delete(key);
                return CacheRead.miss();
            }
            return CacheRead.hit(Optional.of(mapper.readValue(json, AlertRuleEntity.class)));
        } catch (Exception e) {
            log.warn("Failed to read alert rule cache {}: {}", key, e.getMessage());
            deleteBadKey(key);
            return CacheRead.redisUnavailable();
        }
    }

    private List<AlertRuleEntity> loadAndCacheList(String key, Supplier<List<AlertRuleEntity>> loader) {
        List<AlertRuleEntity> rules = loadList(loader);
        writeList(key, rules);
        return rules;
    }

    private Optional<AlertRuleEntity> loadAndCacheOne(String key, Supplier<Optional<AlertRuleEntity>> loader) {
        Optional<AlertRuleEntity> rule = loadOne(loader);
        writeOne(key, rule);
        return rule;
    }

    private List<AlertRuleEntity> loadList(Supplier<List<AlertRuleEntity>> loader) {
        return sanitize(loader == null ? null : loader.get());
    }

    private Optional<AlertRuleEntity> loadOne(Supplier<Optional<AlertRuleEntity>> loader) {
        if (loader == null) {
            return Optional.empty();
        }
        Optional<AlertRuleEntity> loaded = loader.get();
        return loaded == null ? Optional.empty() : loaded.filter(Objects::nonNull);
    }

    private void writeList(String key, List<AlertRuleEntity> rules) {
        try {
            List<AlertRuleEntity> safeRules = sanitize(rules);
            redis.opsForValue().set(key, mapper.writeValueAsString(safeRules), ttlFor(safeRules.isEmpty()));
        } catch (Exception e) {
            log.warn("Failed to write alert rule cache {}: {}", key, e.getMessage());
        }
    }

    private void writeOne(String key, Optional<AlertRuleEntity> rule) {
        try {
            if (rule == null || rule.isEmpty()) {
                redis.opsForValue().set(key, NULL_SENTINEL, ttlFor(true));
                return;
            }
            redis.opsForValue().set(key, mapper.writeValueAsString(rule.get()), ttlFor(false));
        } catch (Exception e) {
            log.warn("Failed to write alert rule cache {}: {}", key, e.getMessage());
        }
    }

    private ReloadLock tryAcquireReloadLock(String cacheKey) {
        String lockKey = reloadLockKey(cacheKey);
        String lockValue = UUID.randomUUID().toString();
        try {
            Boolean locked = redis.opsForValue().setIfAbsent(lockKey, lockValue, RELOAD_LOCK_TTL);
            return Boolean.TRUE.equals(locked) ? ReloadLock.acquired(lockKey, lockValue) : ReloadLock.contended(lockKey);
        } catch (Exception e) {
            log.warn("Failed to acquire alert rule cache reload lock: {}", e.getMessage());
            return ReloadLock.redisUnavailable();
        }
    }

    private void releaseReloadLock(String lockKey, String lockValue) {
        try {
            redis.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockValue);
        } catch (Exception e) {
            log.warn("Failed to release alert rule cache reload lock: {}", e.getMessage());
        }
    }

    private void waitBriefly() {
        try {
            Thread.sleep(RELOAD_WAIT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deleteBadKey(String key) {
        try {
            redis.delete(key);
        } catch (Exception ignored) {
            // Best-effort cleanup only; caller will fall back to MySQL.
        }
    }

    private Duration ttlFor(boolean empty) {
        if (empty) {
            return Duration.ofSeconds(EMPTY_TTL_SECONDS + randomSeconds(EMPTY_TTL_JITTER_SECONDS));
        }
        return Duration.ofSeconds(NORMAL_TTL_SECONDS + randomSeconds(NORMAL_TTL_JITTER_SECONDS));
    }

    private long randomSeconds(long inclusiveMax) {
        return ThreadLocalRandom.current().nextLong(inclusiveMax + 1);
    }

    private List<AlertRuleEntity> sanitize(List<AlertRuleEntity> rules) {
        if (rules == null || rules.isEmpty()) {
            return Collections.emptyList();
        }
        return rules.stream()
            .filter(Objects::nonNull)
            .toList();
    }

    private List<String> cacheKeys() {
        List<String> keys = new java.util.ArrayList<>();
        keys.add(ALL_RULES_KEY);
        for (AlertType type : AlertType.values()) {
            keys.add(ruleByTypeKey(type));
        }
        return keys;
    }

    private static String ruleByTypeKey(AlertType type) {
        return RULE_BY_TYPE_KEY_PREFIX + type.name();
    }

    private static String reloadLockKey(String cacheKey) {
        return RELOAD_LOCK_KEY_PREFIX + cacheKey.replace(':', '-');
    }

    private static StringRedisTemplate createRedis(ObjectProvider<RedisConnectionFactory> connectionFactoryProvider) {
        RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        if (connectionFactory == null) {
            return null;
        }
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    private static ObjectMapper defaultMapper() {
        return new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private record CacheRead<T>(boolean redisAvailable, T value) {
        static <T> CacheRead<T> hit(T value) {
            return new CacheRead<>(true, value);
        }

        static <T> CacheRead<T> miss() {
            return new CacheRead<>(true, null);
        }

        static <T> CacheRead<T> redisUnavailable() {
            return new CacheRead<>(false, null);
        }

        boolean hit() {
            return value != null;
        }
    }

    private record ReloadLock(boolean redisAvailable, String key, String value) {
        static ReloadLock acquired(String key, String value) {
            return new ReloadLock(true, key, value);
        }

        static ReloadLock contended(String key) {
            return new ReloadLock(true, key, null);
        }

        static ReloadLock redisUnavailable() {
            return new ReloadLock(false, null, null);
        }

        boolean acquired() {
            return value != null;
        }
    }
}
