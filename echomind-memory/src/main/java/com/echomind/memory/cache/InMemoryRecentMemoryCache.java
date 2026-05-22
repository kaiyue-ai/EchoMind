package com.echomind.memory.cache;

import com.echomind.common.model.AgentMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存版近期缓存。
 *
 * <p>Redis 不可用或测试环境使用它。它不承担正式持久化职责，
 * 进程重启后只保留新写入的短期上下文，不从 MySQL 回源参与 LLM prompt。</p>
 */
public class InMemoryRecentMemoryCache implements RecentMemoryCache {

    private final int maxMessages;
    private final int maxChars;
    private final int maxMessageChars;
    private final Map<String, Deque<AgentMessage>> windows = new ConcurrentHashMap<>();
    private final Map<String, Integer> windowChars = new ConcurrentHashMap<>();

    public InMemoryRecentMemoryCache(int maxMessages) {
        this(maxMessages, 12000, 1500);
    }

    public InMemoryRecentMemoryCache(int maxMessages, int maxChars, int maxMessageChars) {
        this.maxMessages = Math.max(1, maxMessages);
        this.maxChars = Math.max(200, maxChars);
        this.maxMessageChars = Math.max(100, maxMessageChars);
    }

    @Override
    public void append(String sessionId, AgentMessage message) {
        Deque<AgentMessage> deque = windows.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (deque) {
            AgentMessage cachedMessage = truncateMessage(message);
            deque.addLast(cachedMessage);
            windowChars.merge(sessionId, messageChars(cachedMessage), Integer::sum);
            while (deque.size() > maxMessages) {
                removeFirst(sessionId, deque);
            }
            while (windowChars.getOrDefault(sessionId, 0) > maxChars && deque.size() > 1) {
                removeFirst(sessionId, deque);
            }
        }
    }

    @Override
    public List<AgentMessage> recent(String sessionId) {
        Deque<AgentMessage> deque = windows.get(sessionId);
        if (deque == null) {
            return Collections.emptyList();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    @Override
    public void clear(String sessionId) {
        windows.remove(sessionId);
        windowChars.remove(sessionId);
    }

    @Override
    public Set<String> sessionIds() {
        return Set.copyOf(windows.keySet());
    }

    @Override
    public int size(String sessionId) {
        Deque<AgentMessage> deque = windows.get(sessionId);
        if (deque == null) {
            return 0;
        }
        synchronized (deque) {
            return deque.size();
        }
    }

    private void removeFirst(String sessionId, Deque<AgentMessage> deque) {
        AgentMessage removed = deque.removeFirst();
        windowChars.compute(sessionId, (key, oldValue) ->
            Math.max(0, (oldValue == null ? 0 : oldValue) - messageChars(removed)));
    }

    private AgentMessage truncateMessage(AgentMessage message) {
        if (message == null || message.content() == null || message.content().length() <= maxMessageChars) {
            return message;
        }
        String content = message.content();
        String truncated = content.substring(0, Math.max(0, maxMessageChars - 3)) + "...";
        return new AgentMessage(message.role(), truncated, message.timestamp(), message.metadata(), message.attachments());
    }

    private int messageChars(AgentMessage message) {
        if (message == null || message.content() == null) {
            return 0;
        }
        return message.content().length();
    }
}
