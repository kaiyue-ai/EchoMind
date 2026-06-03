package com.echomind.console.sensitive;

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
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Slf4j
@Component
public class SensitiveRuleCache {

    static final String ALL_RULES_KEY = "echomind:sensitive:rules:all";
    static final String ENABLED_RULES_KEY = "echomind:sensitive:rules:enabled";
    private static final String RELOAD_LOCK_KEY = "echomind:sensitive:rules:reload-lock";

    private static final long NORMAL_TTL_SECONDS = 600;
    private static final long NORMAL_TTL_JITTER_SECONDS = 120;
    private static final long EMPTY_TTL_SECONDS = 30;
    private static final long EMPTY_TTL_JITTER_SECONDS = 10;
    private static final Duration RELOAD_LOCK_TTL = Duration.ofSeconds(5);
    private static final Duration RELOAD_WAIT = Duration.ofMillis(60);

    private static final TypeReference<List<SensitiveRuleEntity>> RULE_LIST_TYPE = new TypeReference<>() {
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
    public SensitiveRuleCache(ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
                              ObjectProvider<ObjectMapper> mapperProvider) {
        this(createRedis(connectionFactoryProvider), mapperProvider.getIfAvailable(SensitiveRuleCache::defaultMapper));
    }

    SensitiveRuleCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper == null ? defaultMapper() : mapper.copy().findAndRegisterModules();
    }

    static SensitiveRuleCache disabled() {
        return new SensitiveRuleCache(null, defaultMapper());
    }

    public List<SensitiveRuleEntity> allRules(Supplier<List<SensitiveRuleEntity>> loader) {
        return getOrLoad(ALL_RULES_KEY, loader);
    }

    public List<SensitiveRuleEntity> enabledRules(Supplier<List<SensitiveRuleEntity>> loader) {
        return getOrLoad(ENABLED_RULES_KEY, loader);
    }

    public void invalidateRules() {
        if (redis == null) {
            return;
        }
        try {
            redis.delete(List.of(ALL_RULES_KEY, ENABLED_RULES_KEY));
        } catch (Exception e) {
            log.warn("Failed to invalidate sensitive rule cache: {}", e.getMessage());
        }
    }

    private List<SensitiveRuleEntity> getOrLoad(String key, Supplier<List<SensitiveRuleEntity>> loader) {
        if (redis == null) {
            return load(loader);
        }
        CacheRead cached = readCached(key);
        if (cached.hit()) {
            return cached.rules();
        }
        if (!cached.redisAvailable()) {
            return load(loader);
        }

        ReloadLock lock = tryAcquireReloadLock();
        if (!lock.redisAvailable()) {
            return load(loader);
        }
        if (!lock.acquired()) {
            waitBriefly();
            cached = readCached(key);
            if (cached.hit()) {
                return cached.rules();
            }
            if (!cached.redisAvailable()) {
                return load(loader);
            }
            return loadAndCache(key, loader);
        }

        try {
            return loadAndCache(key, loader);
        } finally {
            releaseReloadLock(lock.value());
        }
    }

    private CacheRead readCached(String key) {
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
            log.warn("Failed to read sensitive rule cache {}: {}", key, e.getMessage());
            try {
                redis.delete(key);
            } catch (Exception ignored) {
                // Best-effort cleanup only; the caller will fall back to MySQL.
            }
            return CacheRead.redisUnavailable();
        }
    }

    private List<SensitiveRuleEntity> loadAndCache(String key, Supplier<List<SensitiveRuleEntity>> loader) {
        List<SensitiveRuleEntity> rules = load(loader);
        writeCache(key, rules);
        return rules;
    }

    private List<SensitiveRuleEntity> load(Supplier<List<SensitiveRuleEntity>> loader) {
        return sanitize(loader == null ? null : loader.get());
    }

    private void writeCache(String key, List<SensitiveRuleEntity> rules) {
        try {
            List<SensitiveRuleEntity> safeRules = sanitize(rules);
            redis.opsForValue().set(key, mapper.writeValueAsString(safeRules), ttlFor(safeRules));
        } catch (Exception e) {
            log.warn("Failed to write sensitive rule cache {}: {}", key, e.getMessage());
        }
    }

    private ReloadLock tryAcquireReloadLock() {
        String lockValue = UUID.randomUUID().toString();
        try {
            Boolean locked = redis.opsForValue().setIfAbsent(RELOAD_LOCK_KEY, lockValue, RELOAD_LOCK_TTL);
            return Boolean.TRUE.equals(locked) ? ReloadLock.acquired(lockValue) : ReloadLock.contended();
        } catch (Exception e) {
            log.warn("Failed to acquire sensitive rule cache reload lock: {}", e.getMessage());
            return ReloadLock.redisUnavailable();
        }
    }

    private void releaseReloadLock(String lockValue) {
        try {
            redis.execute(RELEASE_LOCK_SCRIPT, List.of(RELOAD_LOCK_KEY), lockValue);
        } catch (Exception e) {
            log.warn("Failed to release sensitive rule cache reload lock: {}", e.getMessage());
        }
    }

    private void waitBriefly() {
        try {
            Thread.sleep(RELOAD_WAIT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Duration ttlFor(List<SensitiveRuleEntity> rules) {
        if (rules == null || rules.isEmpty()) {
            return Duration.ofSeconds(EMPTY_TTL_SECONDS + randomSeconds(EMPTY_TTL_JITTER_SECONDS));
        }
        return Duration.ofSeconds(NORMAL_TTL_SECONDS + randomSeconds(NORMAL_TTL_JITTER_SECONDS));
    }

    private long randomSeconds(long inclusiveMax) {
        return ThreadLocalRandom.current().nextLong(inclusiveMax + 1);
    }

    private List<SensitiveRuleEntity> sanitize(List<SensitiveRuleEntity> rules) {
        if (rules == null || rules.isEmpty()) {
            return Collections.emptyList();
        }
        return rules.stream()
            .filter(Objects::nonNull)
            .toList();
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

    private record CacheRead(boolean redisAvailable, List<SensitiveRuleEntity> rules) {
        static CacheRead hit(List<SensitiveRuleEntity> rules) {
            return new CacheRead(true, rules);
        }

        static CacheRead miss() {
            return new CacheRead(true, null);
        }

        static CacheRead redisUnavailable() {
            return new CacheRead(false, null);
        }

        boolean hit() {
            return rules != null;
        }
    }

    private record ReloadLock(boolean redisAvailable, String value) {
        static ReloadLock acquired(String value) {
            return new ReloadLock(true, value);
        }

        static ReloadLock contended() {
            return new ReloadLock(true, null);
        }

        static ReloadLock redisUnavailable() {
            return new ReloadLock(false, null);
        }

        boolean acquired() {
            return value != null;
        }
    }
}
