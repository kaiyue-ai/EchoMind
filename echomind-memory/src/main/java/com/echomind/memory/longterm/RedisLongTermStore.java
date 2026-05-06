package com.echomind.memory.longterm;

import com.echomind.common.model.AgentMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的长期记忆存储实现 —— 提供分布式、高性能的会话记忆持久化。
 *
 * <p>相比 {@link FileLongTermStore} 的优势：
 * <ul>
 *   <li><b>分布式共享</b>：多个应用实例可共享同一 Redis 实例的记忆数据，
 *       适配水平扩展架构。</li>
 *   <li><b>高性能</b>：Redis 的内存操作远快于文件 I/O，
 *       适合高并发对话场景。</li>
 *   <li><b>TTL 自动过期</b>：支持会话级过期时间，无需手动清理旧数据。</li>
 *   <li><b>原子追加</b>：使用 Redis List 的 {@code RPUSH} 命令原子追加，
 *       无需 read-modify-write 模式。</li>
 * </ul>
 *
 * <p>存储结构：
 * <ul>
 *   <li>每个会话对应一个 Redis Key：{@code echomind:memory:<sessionId>}</li>
 *   <li>消息以 JSON 字符串形式存储在 Redis List 中（每条消息一个元素）</li>
 *   <li>支持可选的 TTL 过期时间，过期后会话记忆自动清理</li>
 * </ul>
 *
 * <p>设计决策：
 * <ul>
 *   <li>使用 {@link StringRedisTemplate} 而非 {@code RedisTemplate}，
 *       直接操作 JSON 字符串，避免序列化兼容性问题。</li>
 *   <li>save 使用 {@code RPUSH} 原子追加，load 使用 {@code LRANGE} 全量加载。</li>
 *   <li>delete 使用 {@code DEL} 直接删除 Key。</li>
 *   <li>query 使用 Java Stream 内存过滤，复杂度 O(n)；
 *       若需高性能全文检索，建议升级为 RedisSearch 模块。</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see LongTermMemoryStore
 * @see FileLongTermStore
 * @since 1.0
 */
public class RedisLongTermStore implements LongTermMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisLongTermStore.class);

    /** Redis Key 前缀，用于隔离不同应用的数据 */
    private static final String KEY_PREFIX = "echomind:memory:";

    /**
     * 共享的 Jackson ObjectMapper，用于消息的 JSON 序列化/反序列化。
     * 注册 JSR310 模块并禁用时间戳格式，确保日期以 ISO-8601 字符串存储。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Spring Data Redis 的字符串模板，提供线程安全的高级 Redis 操作 */
    private final StringRedisTemplate redis;

    /** 会话记忆的 TTL 秒数；0 或负数表示永不过期 */
    private final long ttlSeconds;

    /**
     * 构造 Redis 长期存储实例。
     *
     * @param connectionFactory Redis 连接工厂（由 Spring Boot 自动配置提供）
     * @param ttlSeconds        会话记忆的过期时间（秒），<=0 表示永不过期
     */
    public RedisLongTermStore(RedisConnectionFactory connectionFactory, long ttlSeconds) {
        this.redis = new StringRedisTemplate(connectionFactory);
        this.redis.afterPropertiesSet();
        this.ttlSeconds = ttlSeconds;
        log.info("RedisLongTermStore initialized, TTL={}s", ttlSeconds);
    }

    /**
     * 将消息列表原子追加到 Redis List 的尾部。
     *
     * <p>使用 {@code RPUSH} 命令一次性批量追加所有消息，
     * 然后通过 {@code EXPIRE} 刷新 TTL（如已配置）。
     *
     * @param sessionId 会话唯一标识
     * @param messages  要持久化的消息列表
     */
    @Override
    public void save(String sessionId, List<AgentMessage> messages) {
        String key = sessionKey(sessionId);
        try {
            String[] jsonArray = messages.stream()
                .map(this::toJson)
                .toArray(String[]::new);
            redis.opsForList().rightPushAll(key, jsonArray);
            if (ttlSeconds > 0) {
                redis.expire(key, ttlSeconds, TimeUnit.SECONDS);
            }
            log.debug("Saved {} messages to Redis for session {}", messages.size(), sessionId);
        } catch (Exception e) {
            log.error("Failed to save to Redis for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 加载指定会话的全部长期记忆（从 Redis List 全量加载）。
     *
     * <p>使用 {@code LRANGE 0 -1} 获取 List 中所有元素，
     * 然后逐条 JSON 反序列化为 AgentMessage。
     *
     * @param sessionId 会话唯一标识
     * @return 该会话的全部历史消息；无数据时返回空列表
     */
    @Override
    public List<AgentMessage> load(String sessionId) {
        String key = sessionKey(sessionId);
        try {
            List<String> jsonList = redis.opsForList().range(key, 0, -1);
            if (jsonList == null || jsonList.isEmpty()) {
                return Collections.emptyList();
            }
            return jsonList.stream()
                .map(this::fromJson)
                .filter(Objects::nonNull)
                .toList();
        } catch (Exception e) {
            log.warn("Failed to load from Redis for session {}: {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按关键词查询长期记忆。
     *
     * <p>先将整个 List 加载到内存，再用 Java Stream 过滤。
     * 对于超长会话记忆（>10000 条），建议在生产环境中使用 RedisSearch 模块。
     *
     * @param sessionId 会话唯一标识
     * @param keyword   搜索关键词（大小写不敏感）
     * @return 匹配的消息列表；无匹配时返回空列表
     */
    @Override
    public List<AgentMessage> query(String sessionId, String keyword) {
        return load(sessionId).stream()
            .filter(m -> m.content() != null && m.content().toLowerCase().contains(keyword.toLowerCase()))
            .toList();
    }

    /**
     * 删除指定会话的 Redis Key。
     *
     * <p>操作是幂等的：Key 不存在时 {@code DEL} 命令不报错。
     *
     * @param sessionId 要删除的会话唯一标识
     */
    @Override
    public void delete(String sessionId) {
        try {
            redis.delete(sessionKey(sessionId));
            log.debug("Deleted Redis memory for session {}", sessionId);
        } catch (Exception e) {
            log.warn("Failed to delete Redis memory for session {}: {}", sessionId, e.getMessage());
        }
    }

    /** 拼装 Redis Key */
    private String sessionKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    /** 将 AgentMessage 序列化为 JSON 字符串 */
    private String toJson(AgentMessage msg) {
        try {
            return MAPPER.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message: {}", e.getMessage());
            return "{}";
        }
    }

    /** 从 JSON 字符串反序列化为 AgentMessage */
    private AgentMessage fromJson(String json) {
        try {
            return MAPPER.readValue(json, AgentMessage.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize message: {}", e.getMessage());
            return null;
        }
    }
}
