package com.echomind.console.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.console.auth.AuthContext;
import com.echomind.memory.MemoryManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 记忆应用服务。
 *
 * <p>普通聊天记忆按当前认证用户和 sessionId 隔离。Controller 不接收可信 userId，
 * 完整历史、近期缓存、摘要和向量检索都由 MemoryManager 统一处理。</p>
 */
@Service
@RequiredArgsConstructor
public class MemoryApplicationService {

    private final MemoryManager memoryManager;

    public List<AgentMessage> getMemory(String sessionId) {
        validateSessionId(sessionId);
        return memoryManager.getFullContext(AuthContext.userId(), sessionId);
    }

    public Map<String, String> clearMemory(String sessionId) {
        validateSessionId(sessionId);
        memoryManager.clearSession(AuthContext.userId(), sessionId);
        return Map.of("status", "cleared", "sessionId", sessionId);
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
    }
}
