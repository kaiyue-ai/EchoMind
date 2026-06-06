package com.echomind.console.service;

import com.echomind.agent.pipeline.planning.MemoryDecisionParser;
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
import java.util.Set;

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

    private static final Set<String> DISPLAY_ROLES = Set.of("user", "assistant", "system");

    private final MemoryManager memoryManager;
    private final ObjectStorageService storageService;

    public List<SessionSummary> listSessions() {
        try {
            return memoryManager.listSessions(AuthContext.userId()).stream()
                .map(this::stripHiddenMemoryDecision)
                .toList();
        } catch (Exception e) {
            log.warn("Failed to list chat sessions: {}", e.getMessage());
            return List.of();
        }
    }

    public List<AgentMessage> getChatHistory(String sessionId) {
        validateSessionId(sessionId);
        return memoryManager.getFullContext(AuthContext.userId(), sessionId).stream()
            .filter(this::isDisplayableMessage)
            .map(this::stripHiddenMemoryDecision)
            .map(this::refreshAttachmentUrls)
            .toList();
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

    private boolean isDisplayableMessage(AgentMessage message) {
        return message != null && DISPLAY_ROLES.contains(message.role());
    }

    private AgentMessage stripHiddenMemoryDecision(AgentMessage message) {
        String stripped = MemoryDecisionParser.stripHiddenDecisionBlocks(message.content());
        if (stripped == null || stripped.equals(message.content())) {
            return message;
        }
        return new AgentMessage(message.role(), stripped, message.timestamp(), message.metadata(), message.attachments());
    }

    private SessionSummary stripHiddenMemoryDecision(SessionSummary summary) {
        String stripped = MemoryDecisionParser.stripHiddenDecisionBlocks(summary.lastMessage());
        if (stripped == null || stripped.equals(summary.lastMessage())) {
            return summary;
        }
        return new SessionSummary(summary.sessionId(), summary.messageCount(), stripped, summary.lastActivity());
    }

    private MessageAttachment refreshAttachmentUrl(MessageAttachment attachment) {
        if (attachment == null || attachment.uri() == null || !storageService.supports(attachment.uri())) {
            return attachment;
        }
        return attachment.withUrl(storageService.urlFor(attachment.uri(), Duration.ofDays(7)));
    }
}
