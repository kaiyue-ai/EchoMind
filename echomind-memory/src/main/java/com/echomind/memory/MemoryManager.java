package com.echomind.memory;

import com.echomind.common.model.AgentMessage;
import com.echomind.memory.longterm.LongTermMemoryStore;
import com.echomind.memory.session.SessionConfig;
import com.echomind.memory.session.SessionManager;
import com.echomind.memory.shortterm.ConversationWindow;
import com.echomind.memory.shortterm.WindowConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆管理器 —— Agent 记忆系统的中央调度组件。
 *
 * <p>职责概述：
 * <ol>
 *   <li><b>短期记忆：</b>每个会话维护独立的 {@link ConversationWindow}，
 *       通过 {@code ConcurrentHashMap<String, ConversationWindow>} 实现会话隔离。</li>
 *   <li><b>长期记忆：</b>委托给 {@link LongTermMemoryStore}，采用写穿（write-through）策略：
 *       每条消息在加入短期窗口的同时立即写入长期存储，确保崩溃安全。</li>
 *   <li><b>会话管理：</b>委托给 {@link SessionManager}，创建、查询和销毁用户会话。</li>
 *   <li><b>全量上下文：</b>合并长期记忆 + 本会话短期记忆，提供 Agent 推理所需的完整对话上下文。</li>
 * </ol>
 *
 * <p>关键设计决策：
 * <ul>
 *   <li><b>会话隔离</b>：每个会话拥有独立的 ConversationWindow，杜绝跨会话消息污染。</li>
 *   <li><b>写穿策略</b>：addMessage 同时写入短期窗口和长期存储，JVM 崩溃不丢消息。</li>
 *   <li><b>先驱逐后添加</b>：窗口满时先 evict 再 add，避免 ConversationWindow 自动 trim 导致消息丢失。</li>
 *   <li><b>线程安全</b>：ConcurrentHashMap + ConcurrentLinkedDeque 保证并发安全。</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see ConversationWindow
 * @see LongTermMemoryStore
 * @since 1.0
 */
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    /** 长期记忆持久化存储 —— 写穿模式，每条消息立即持久化 */
    private final LongTermMemoryStore longTerm;
    /** 会话管理器 —— 管理会话的生命周期和配置 */
    private final SessionManager sessionManager;
    /** 窗口配置 —— 控制最大消息数、驱逐数量 */
    private final WindowConfig windowConfig;
    /**
     * 每个会话独立的短期记忆窗口 —— Key 为 sessionId，Value 为该会话的 ConversationWindow。
     * 使用 ConcurrentHashMap 支持高并发读写。
     */
    private final Map<String, ConversationWindow> sessionWindows = new ConcurrentHashMap<>();

    /**
     * 构造 MemoryManager，注入所有必需组件。
     *
     * @param windowConfig   短期窗口配置（最大消息数、驱逐数量）
     * @param longTermStore  长期记忆存储实现（文件、Redis 等）
     * @param sessionManager 会话管理器实例
     */
    public MemoryManager(WindowConfig windowConfig, LongTermMemoryStore longTermStore,
                         SessionManager sessionManager) {
        this.windowConfig = windowConfig;
        this.longTerm = longTermStore;
        this.sessionManager = sessionManager;
    }

    /**
     * 向指定会话添加一条消息 —— 写穿模式。
     *
     * <p>流程：
     * <ol>
     *   <li>获取或创建该会话的专属 ConversationWindow</li>
     *   <li>若窗口已满，先驱逐最旧消息（防止后续 add 自动 trim 丢失消息）</li>
     *   <li>消息追加到短期窗口</li>
     *   <li>消息立即写穿到长期存储</li>
     * </ol>
     *
     * @param sessionId 会话唯一标识
     * @param message   要添加的 Agent 消息
     */
    public void addMessage(String sessionId, AgentMessage message) {
        ConversationWindow window = sessionWindows.computeIfAbsent(sessionId,
            k -> new ConversationWindow(windowConfig));

        // 先驱逐后添加：防止 ConversationWindow.add() 的自动 trim 静默丢弃消息
        if (window.isFull()) {
            List<AgentMessage> evicted = window.evictOldest(
                Math.max(1, windowConfig.getEvictCount()));
            // 驱逐的消息已在窗口达到上限前通过写穿保存，此处无需重复
        }

        window.add(message);

        // 写穿：立即持久化到长期存储，确崩溃安全
        try {
            longTerm.save(sessionId, List.of(message));
        } catch (Exception e) {
            log.warn("Failed to persist message to long-term store for session {}: {}",
                sessionId, e.getMessage());
        }
    }

    /**
     * 获取指定会话的近期消息（短期窗口内容）。
     *
     * @param sessionId 会话唯一标识
     * @return 该会话最近的对话消息列表；无消息时返回空列表
     */
    public List<AgentMessage> getRecentMessages(String sessionId) {
        ConversationWindow window = sessionWindows.get(sessionId);
        if (window == null) {
            return Collections.emptyList();
        }
        return window.getMessages();
    }

    /**
     * 从长期存储中加载指定会话的历史消息。
     *
     * @param sessionId 会话唯一标识
     * @return 该会话的全部长期历史消息列表
     */
    public List<AgentMessage> getLongTermHistory(String sessionId) {
        return longTerm.load(sessionId);
    }

    /**
     * 获取指定会话的完整上下文 —— 长期历史 + 短期窗口合并。
     *
     * <p>合并策略：先长期历史（时间久远），追加短期消息（最新），按时间升序排列。
     * 由于采用写穿策略，长期存储已包含所有消息，短期窗口作为缓存加速读取。
     * 为避免重复，合并时以长期存储为主，用消息时间戳去重。
     *
     * @param sessionId 会话唯一标识
     * @return 合并后的完整消息列表（长期 + 短期去重，按时间升序）
     */
    public List<AgentMessage> getFullContext(String sessionId) {
        List<AgentMessage> context = new ArrayList<>(getLongTermHistory(sessionId));
        List<AgentMessage> recent = getRecentMessages(sessionId);

        // 用时间戳去重：长期存储已有写入的消息，但短期窗口可能尚未 evict
        if (!context.isEmpty() && !recent.isEmpty()) {
            AgentMessage lastPersisted = context.get(context.size() - 1);
            for (AgentMessage msg : recent) {
                if (msg.timestamp() != null && lastPersisted.timestamp() != null
                    && msg.timestamp().isAfter(lastPersisted.timestamp())) {
                    context.add(msg);
                }
            }
        } else {
            context.addAll(recent);
        }
        return context;
    }

    /**
     * 清除指定会话的全部记忆 —— 移除短期窗口并删除长期存储中该会话的数据。
     *
     * @param sessionId 要清除的会话唯一标识
     */
    public void clearSession(String sessionId) {
        sessionWindows.remove(sessionId);
        longTerm.delete(sessionId);
    }

    /**
     * 查询指定会话短期记忆窗口中的消息数量。
     *
     * @param sessionId 会话唯一标识
     * @return 短期记忆窗口大小；会话不存在时返回 0
     */
    public int shortTermSize(String sessionId) {
        ConversationWindow window = sessionWindows.get(sessionId);
        return window != null ? window.size() : 0;
    }

    /**
     * 查询当前活跃会话总数。
     *
     * @return 活跃会话数
     */
    public int activeSessionCount() {
        return sessionWindows.size();
    }
}
