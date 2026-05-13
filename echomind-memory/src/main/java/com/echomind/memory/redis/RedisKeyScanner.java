package com.echomind.memory.redis;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Redis key 扫描工具。
 *
 * <p>生产环境不能使用 {@code KEYS pattern}，因为它会阻塞 Redis 主线程并扫描整个 keyspace。
 * 本工具统一使用 {@code SCAN MATCH pattern COUNT n} 分批游标扫描，适合会话、向量和知识库
 * 这类“按前缀批量查找/删除”的场景。</p>
 */
public class RedisKeyScanner {

    private static final long DEFAULT_COUNT = 1000L;

    private final StringRedisTemplate redis;
    private final long count;

    public RedisKeyScanner(StringRedisTemplate redis) {
        this(redis, DEFAULT_COUNT);
    }

    public RedisKeyScanner(StringRedisTemplate redis, long count) {
        this.redis = redis;
        this.count = Math.max(1, count);
    }

    /** 按 Redis glob pattern 分批扫描 key。 */
    public Set<String> scan(String pattern) {
        if (redis == null || pattern == null || pattern.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> keys = redis.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> result = new LinkedHashSet<>();
            ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(count)
                .build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    result.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return result;
        });
        return keys == null ? Collections.emptySet() : keys;
    }

    /** 按 pattern 扫描并批量删除 key，返回 Redis 实际删除的数量。 */
    public long deleteByPattern(String pattern) {
        Set<String> keys = scan(pattern);
        if (keys.isEmpty()) {
            return 0;
        }
        Long deleted = redis.delete(keys);
        return deleted == null ? 0 : deleted;
    }
}
