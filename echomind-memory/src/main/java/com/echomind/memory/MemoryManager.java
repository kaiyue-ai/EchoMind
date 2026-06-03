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

    public void addMessage(String userId, String memoryKey, String agentId, AgentMessage message) {
        if (isBlank(memoryKey) || message == null) {
            return;
        }
        String cacheKey = memoryKey(userId, memoryKey);
        chatStore.saveMessage(normalizeUserId(userId), memoryKey, agentId, message);
        recentCache.append(cacheKey, message);
        refreshSummaryIfNeeded(normalizeUserId(userId), memoryKey);
    }

    private List<AgentMessage> getRecentMessages(String memoryKey) {
        return recentCache.recent(memoryKey);
    }

    public List<AgentMessage> getFullContext(String userId, String memoryKey) {
        return chatStore.loadFullHistory(normalizeUserId(userId), memoryKey);
    }

    public List<AgentMessage> getPromptContext(String memoryKey) {
        return getPromptContext("default", memoryKey);
    }

    public List<AgentMessage> getPromptContext(String userId, String memoryKey) {
        List<AgentMessage> result = new ArrayList<>();
        result.addAll(getRecentMessages(memoryKey(userId, memoryKey)));
        return result;
    }

    public void clearSession(String userId, String memoryKey) {
        String cacheKey = memoryKey(userId, memoryKey);
        recentCache.clear(cacheKey);
        chatStore.deleteSession(normalizeUserId(userId), memoryKey);
    }

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
