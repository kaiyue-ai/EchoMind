package com.echomind.memory;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.SessionSummary;
import com.echomind.memory.cache.RecentMemoryCache;
import com.echomind.memory.embedding.MemoryEmbeddingService;
import com.echomind.memory.embedding.MemorySearchHit;
import com.echomind.memory.persistence.ChatMessageEntity;
import com.echomind.memory.persistence.PersistentChatMemoryStore;
import com.echomind.memory.shortterm.WindowConfig;
import com.echomind.memory.summary.MemorySummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话记忆编排器。
 *
 * <p>当前策略是“一次对话历史一份记忆”：
 * <ul>
 *   <li>MySQL：完整会话、消息、摘要和向量索引，是事实来源。</li>
 *   <li>Redis：只保存最近 N 条消息，作为提示词热缓存。</li>
 *   <li>向量检索：用当前用户问题召回同一会话里的相关历史。</li>
 * </ul>
 *
 * <p>注意：{@link #getFullContext(String)} 是给页面历史和管理接口用的；
 * 模型调用必须使用 {@link #getPromptContext(String, String)}，否则会重新把全量历史塞进提示词。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class MemoryManager {

    /** 近期窗口配置，控制 Redis 最近上下文条数。 */
    private final WindowConfig windowConfig;
    /** MySQL 正式会话记忆。 */
    private final PersistentChatMemoryStore chatStore;
    /** Redis 或本地内存近期缓存。 */
    private final RecentMemoryCache recentCache;
    /** 向量索引与相似历史检索。 */
    private final MemoryEmbeddingService embeddingService;
    /** 摘要生成服务。 */
    private final MemorySummaryService summaryService;
    /** 相关历史召回条数。 */
    private final int retrievalTopK;
    /** 每隔多少条消息刷新一次摘要，避免每轮都扫全量历史。 */
    private final int summaryRefreshInterval;

    /**
     * 保存消息，兼容旧调用方。
     *
     * @param memoryKey 会话 ID
     * @param message   消息
     */
    public void addMessage(String memoryKey, AgentMessage message) {
        addMessage(memoryKey, null, message);
    }

    /**
     * 保存消息到 MySQL，并同步刷新 Redis 近期缓存和向量索引。
     *
     * @param memoryKey 会话 ID
     * @param agentId   Agent ID，可为空
     * @param message   消息
     */
    public void addMessage(String memoryKey, String agentId, AgentMessage message) {
        if (isBlank(memoryKey) || message == null) {
            return;
        }
        ChatMessageEntity saved = chatStore.saveMessage(memoryKey, agentId, message);
        recentCache.append(memoryKey, message);
        embeddingService.indexMessage(memoryKey, saved.getId(), message);
        refreshSummaryIfNeeded(memoryKey);
    }

    /**
     * 读取 Redis 里的近期消息；缓存空时从 MySQL 最近 N 条回源。
     *
     * @param memoryKey 会话 ID
     * @return 最近消息，按时间升序
     */
    public List<AgentMessage> getRecentMessages(String memoryKey) {
        List<AgentMessage> cached = recentCache.recent(memoryKey);
        if (!cached.isEmpty()) {
            return cached;
        }
        List<AgentMessage> fromDb = chatStore.loadRecent(memoryKey, windowConfig.getMaxMessages());
        fromDb.forEach(message -> recentCache.append(memoryKey, message));
        return fromDb;
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

    /**
     * 构造模型提示词上下文。
     *
     * <p>上下文由三段组成：早期摘要、相关历史、最近 N 条消息。
     * 当前用户消息由管线最后追加，因此这里不会重复加入。</p>
     *
     * @param memoryKey          会话 ID
     * @param currentUserMessage 当前用户问题
     * @return 可直接传给模型的历史上下文
     */
    public List<AgentMessage> getPromptContext(String memoryKey, String currentUserMessage) {
        List<AgentMessage> result = new ArrayList<>();
        String summary = chatStore.loadSummary(memoryKey);
        if (!isBlank(summary)) {
            result.add(AgentMessage.system("【会话摘要】\n" + summary));
        }

        List<AgentMessage> recent = getRecentMessages(memoryKey);
        Set<String> recentFingerprints = fingerprints(recent);
        List<MemorySearchHit> hits = embeddingService.search(memoryKey, currentUserMessage, retrievalTopK).stream()
            .filter(hit -> !recentFingerprints.contains(fingerprint(hit.role(), hit.content())))
            .toList();
        if (!hits.isEmpty()) {
            result.add(AgentMessage.system(formatRelevantHistory(hits)));
        }

        result.addAll(recent);
        return result;
    }

    /** 清除指定会话的全部正式历史、向量和近期缓存。 */
    public void clearSession(String memoryKey) {
        recentCache.clear(memoryKey);
        embeddingService.deleteSession(memoryKey);
        chatStore.deleteSession(memoryKey);
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

    private void refreshSummaryIfNeeded(String memoryKey) {
        long count = chatStore.countMessages(memoryKey);
        int interval = Math.max(1, summaryRefreshInterval);
        if (count <= windowConfig.getMaxMessages() || count % interval != 0) {
            return;
        }
        try {
            String summary = summaryService.summarize(chatStore.loadFullHistory(memoryKey));
            chatStore.updateSummary(memoryKey, summary);
        } catch (Exception e) {
            log.warn("Failed to refresh memory summary for session {}: {}", memoryKey, e.getMessage());
        }
    }

    private String formatRelevantHistory(List<MemorySearchHit> hits) {
        StringBuilder sb = new StringBuilder("【相关历史】以下片段来自同一会话的历史记录，可作为回答参考：\n");
        for (MemorySearchHit hit : hits) {
            sb.append("- ")
                .append(roleLabel(hit.role()))
                .append("：")
                .append(hit.content())
                .append("\n");
        }
        return sb.toString().trim();
    }

    private Set<String> fingerprints(List<AgentMessage> messages) {
        Set<String> set = new HashSet<>();
        for (AgentMessage message : messages) {
            set.add(fingerprint(message.role(), message.content()));
        }
        return set;
    }

    private String fingerprint(String role, String content) {
        return (role == null ? "" : role) + "::" + (content == null ? "" : content);
    }

    private String roleLabel(String role) {
        return switch (role) {
            case "user" -> "用户";
            case "assistant" -> "助手";
            case "tool" -> "工具";
            case "system" -> "系统";
            default -> role == null ? "未知" : role;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
