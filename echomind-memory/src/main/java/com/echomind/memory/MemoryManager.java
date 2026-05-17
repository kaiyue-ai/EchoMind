package com.echomind.memory;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.SessionSummary;
import com.echomind.memory.cache.RecentMemoryCache;
import com.echomind.memory.persistence.PersistentChatMemoryStore;
import com.echomind.memory.shortterm.WindowConfig;
import com.echomind.memory.summary.MemorySummaryService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆编排器。
 *
 * <p>两层存储：
 * <ul>
 *   <li>MySQL：完整会话、消息、摘要，服务前端历史展示和审计。</li>
 *   <li>Redis：保存最近 N 条消息，是 LLM prompt 的短期上下文来源。</li>
 * </ul>
 *
 * <p>注意：模型调用只使用 Redis 近期上下文；Redis miss 时不回源 MySQL，避免把历史读库带回主链路。</p>
 */
@Slf4j
public class MemoryManager {

    private final WindowConfig windowConfig;
    private final PersistentChatMemoryStore chatStore;
    private final RecentMemoryCache recentCache;
    private final MemorySummaryService summaryService;
    private final int summaryRefreshInterval;

    public MemoryManager(WindowConfig windowConfig,
                         PersistentChatMemoryStore chatStore,
                         RecentMemoryCache recentCache,
                         MemorySummaryService summaryService,
                         int summaryRefreshInterval) {
        this.windowConfig = windowConfig;
        this.chatStore = chatStore;
        this.recentCache = recentCache;
        this.summaryService = summaryService;
        this.summaryRefreshInterval = summaryRefreshInterval;
    }

    /**
     * 保存消息，兼容旧调用方。
     *
     * @param memoryKey 会话 ID
     * @param message   消息
     */
    public void addMessage(String memoryKey, AgentMessage message) {
        addMessage(memoryKey, null, message);
    }

    /** 保存消息到 MySQL 并刷新 Redis 近期缓存。只允许异步消费者调用。 */
    public void addMessage(String memoryKey, String agentId, AgentMessage message) {
        if (isBlank(memoryKey) || message == null) {
            return;
        }
        chatStore.saveMessage("default", memoryKey, agentId, message);
        recentCache.append(memoryKey, message);
        refreshSummaryIfNeeded("default", memoryKey);
    }

    /** 保存用户会话消息到 MySQL 并刷新 Redis 近期缓存。只允许异步消费者调用。 */
    public void addMessage(String userId, String memoryKey, String agentId, AgentMessage message) {
        if (isBlank(memoryKey) || message == null) {
            return;
        }
        String cacheKey = memoryKey(userId, memoryKey);
        chatStore.saveMessage(normalizeUserId(userId), memoryKey, agentId, message);
        recentCache.append(cacheKey, message);
        refreshSummaryIfNeeded(normalizeUserId(userId), memoryKey);
    }

    /** 读取 Redis 里的近期消息；缓存空时不回源 MySQL。 */
    public List<AgentMessage> getRecentMessages(String memoryKey) {
        return recentCache.recent(memoryKey);
    }

    /**
     * 完整历史只从 MySQL 读取。
     *
     * @param memoryKey 会话 ID
     * @return 完整消息历史，按时间升序
     */
    public List<AgentMessage> getFullContext(String memoryKey) {
        return chatStore.loadFullHistory(memoryKey);
    }

    /** 完整历史只从 MySQL 读取，并按用户隔离。 */
    public List<AgentMessage> getFullContext(String userId, String memoryKey) {
        return chatStore.loadFullHistory(normalizeUserId(userId), memoryKey);
    }

    /**
     * 构造模型提示词上下文。
     *
     * <p>上下文只来自 Redis 最近 N 条消息。当前用户消息由管线最后追加，因此这里不会重复加入。</p>
     *
     * @param memoryKey 会话 ID
     * @return 可直接传给模型的历史上下文
     */
    public List<AgentMessage> getPromptContext(String memoryKey) {
        List<AgentMessage> result = new ArrayList<>();
        result.addAll(getRecentMessages(memoryKey));
        return result;
    }

    /** 清除指定会话的全部正式历史和近期缓存。 */
    public void clearSession(String memoryKey) {
        recentCache.clear(memoryKey);
        chatStore.deleteSession(memoryKey);
    }

    /** 清除指定用户会话的全部正式历史和近期缓存。 */
    public void clearSession(String userId, String memoryKey) {
        String cacheKey = memoryKey(userId, memoryKey);
        recentCache.clear(cacheKey);
        chatStore.deleteSession(normalizeUserId(userId), memoryKey);
    }

    /** 当前会话近期缓存大小。 */
    public int shortTermSize(String memoryKey) {
        return recentCache.size(memoryKey);
    }

    /** 当前近期缓存可见的会话数量。 */
    public int activeSessionCount() {
        return recentCache.sessionIds().size();
    }

    /** 会话列表从 MySQL 读取，确保重启后仍然完整。 */
    public List<SessionSummary> listSessions() {
        return chatStore.listSessions();
    }

    /** 会话列表从 MySQL 读取，并按用户隔离。 */
    public List<SessionSummary> listSessions(String userId) {
        return chatStore.listSessions(normalizeUserId(userId));
    }

    private void refreshSummaryIfNeeded(String userId, String memoryKey) {
        String owner = normalizeUserId(userId);
        long count = chatStore.countMessages(owner, memoryKey);
        int interval = Math.max(1, summaryRefreshInterval);
        if (count <= windowConfig.getMaxMessages() || count % interval != 0) {
            return;
        }
        try {
            String summary = summaryService.summarize(chatStore.loadFullHistory(owner, memoryKey));
            chatStore.updateSummary(owner, memoryKey, summary);
        } catch (Exception e) {
            log.warn("Failed to refresh memory summary for session {}: {}", memoryKey, e.getMessage());
        }
    }

    private String memoryKey(String userId, String sessionId) {
        String owner = normalizeUserId(userId);
        return sessionId != null && sessionId.startsWith(owner + ":") ? sessionId : owner + ":" + sessionId;
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
