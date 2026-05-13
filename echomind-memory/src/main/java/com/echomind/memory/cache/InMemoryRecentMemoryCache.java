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
 * 进程重启后会从 MySQL 最近消息自动回源。</p>
 */
public class InMemoryRecentMemoryCache implements RecentMemoryCache {

    private final int maxMessages;
    private final Map<String, Deque<AgentMessage>> windows = new ConcurrentHashMap<>();

    public InMemoryRecentMemoryCache(int maxMessages) {
        this.maxMessages = Math.max(1, maxMessages);
    }

    @Override
    public void append(String sessionId, AgentMessage message) {
        Deque<AgentMessage> deque = windows.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(message);
            while (deque.size() > maxMessages) {
                deque.removeFirst();
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
}
