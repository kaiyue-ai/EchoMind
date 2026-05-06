package com.echomind.memory.session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器 —— 管理 Agent 会话的完整生命周期。
 *
 * <p>职责：
 * <ul>
 *   <li>创建会话 —— 生成唯一的 sessionId（UUID v4），并与会话配置关联。</li>
 *   <li>查询会话 —— 检查指定 sessionId 是否存在。</li>
 *   <li>获取配置 —— 返回会话关联的 {@link SessionConfig} 配置。</li>
 *   <li>销毁会话 —— 移除会话及其配置。</li>
 * </ul>
 *
 * <p>会话隔离：
 * 每个 sessionId 对应一个独立的 {@link SessionConfig} 实例，
 * 不同会话可以有不同的 TTL（生存时间）、记忆窗口数量等配置。
 *
 * <p>线程安全：
 * 使用 {@link ConcurrentHashMap} 作为会话存储容器，支持并发创建、查询和移除。
 *
 * <p>设计决策：
 * <ul>
 *   <li>使用 UUID 而非自增 ID —— 防止会话枚举攻击，同时支持分布式部署。</li>
 *   <li>默认配置模板 —— 创建会话时可传入自定义配置或使用默认配置模板，
 *       提供灵活的会话策略定制能力。</li>
 *   <li>无自动过期机制 —— 当前实现不会自动清理过期的会话，
 *       由调用方（如定时任务或 Web 层 Session 监听器）决定何时调用 {@link #removeSession(String)}。</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see SessionConfig
 * @see com.echomind.memory.MemoryManager
 * @since 1.0
 */
public class SessionManager {

    /**
     * 会话存储 —— 线程安全的 Map，Key 为 sessionId，Value 为会话配置。
     * 使用 ConcurrentHashMap 保证高并发下的读写安全。
     */
    private final Map<String, SessionConfig> sessions = new ConcurrentHashMap<>();

    /** 默认会话配置 —— 通过此模板创建新会话时使用，保证配置一致性 */
    private final SessionConfig defaultConfig;

    /**
     * 构造会话管理器。
     *
     * @param defaultConfig 默认会话配置模板，新会话如不指定自定义配置则使用此模板
     */
    public SessionManager(SessionConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    /**
     * 使用默认配置创建一个新会话。
     *
     * <p>新会话的配置是从 defaultConfig 拷贝的独立实例，
     * 因此修改某个会话的配置不会影响其他会话或默认模板。
     *
     * @return 新生成的 UUID 格式 sessionId
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new SessionConfig(defaultConfig));
        return sessionId;
    }

    /**
     * 使用自定义配置创建一个新会话。
     *
     * @param config 自定义会话配置（直接使用传入的引用，不做拷贝）
     * @return 新生成的 UUID 格式 sessionId
     */
    public String createSession(SessionConfig config) {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, config);
        return sessionId;
    }

    /**
     * 检查指定会话是否存在。
     *
     * @param sessionId 会话唯一标识
     * @return 如果会话存在则返回 true，否则返回 false
     */
    public boolean exists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * 获取指定会话的配置。
     *
     * <p>如果会话不存在，返回默认配置而非 null，确保调用方始终能获得有效配置。
     *
     * @param sessionId 会话唯一标识
     * @return 该会话的 SessionConfig，或默认配置（会话不存在时）
     */
    public SessionConfig getConfig(String sessionId) {
        return sessions.getOrDefault(sessionId, defaultConfig);
    }

    /**
     * 移除并销毁指定会话。
     *
     * <p>操作是幂等的：对不存在的 sessionId 调用不会产生副作用。
     *
     * @param sessionId 要移除的会话唯一标识
     */
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * 获取当前活跃会话数量。
     *
     * @return 当前内存中的会话总数
     */
    public int activeSessionCount() {
        return sessions.size();
    }
}
