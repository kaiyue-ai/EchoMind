package com.echomind.console.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MessageAttachment;
import com.echomind.common.model.SessionSummary;
import com.echomind.console.auth.AuthContext;
import com.echomind.memory.MemoryManager;
import com.echomind.skill.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
@Slf4j
public class MemoryApplicationService {

    private final MemoryManager memoryManager;
    private final ObjectStorageService storageService;

    public List<SessionSummary> listSessions() {
        try {
            return memoryManager.listSessions(AuthContext.userId());
        } catch (Exception e) {
            log.warn("Failed to list chat sessions: {}", e.getMessage());
            return List.of();
        }
    }

    public List<AgentMessage> getMemory(String sessionId) {
        validateSessionId(sessionId);
        return memoryManager.getFullContext(AuthContext.userId(), sessionId);
    }

    public List<AgentMessage> getChatHistory(String sessionId) {
        validateSessionId(sessionId);
        return memoryManager.getFullContext(AuthContext.userId(), sessionId).stream()
            .map(this::refreshAttachmentUrls)
            .toList();
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

    private AgentMessage refreshAttachmentUrls(AgentMessage message) {
        if (message.attachments() == null || message.attachments().isEmpty()) {
            return message;
        }
        List<MessageAttachment> refreshed = message.attachments().stream()
            .map(this::refreshAttachmentUrl)
            .toList();
        return new AgentMessage(message.role(), message.content(), message.timestamp(),
            message.metadata(), refreshed);
    }

    private MessageAttachment refreshAttachmentUrl(MessageAttachment attachment) {
        if (attachment == null || attachment.uri() == null || !storageService.supports(attachment.uri())) {
            return attachment;
        }
        return attachment.withUrl(storageService.urlFor(attachment.uri(), Duration.ofDays(7)));
    }
}
