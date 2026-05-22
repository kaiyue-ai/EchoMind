package com.echomind.console.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MessageAttachment;
import com.echomind.memory.MemoryManager;
import com.echomind.skill.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deletes a chat session and best-effort managed attachment objects.
 */
@Service
@RequiredArgsConstructor
@Slf4j
class ChatSessionCleanupService {

    private final MemoryManager memoryManager;
    private final ObjectStorageService storageService;

    Map<String, String> deleteSession(String userId, String sessionId) {
        validateSessionId(sessionId);
        Set<String> attachmentUris = collectManagedAttachmentUris(memoryManager.getFullContext(userId, sessionId));
        memoryManager.clearSession(userId, sessionId);
        deleteAttachmentsQuietly(sessionId, attachmentUris);
        return Map.of("status", "deleted", "sessionId", sessionId);
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
    }

    private Set<String> collectManagedAttachmentUris(List<AgentMessage> history) {
        Set<String> uris = new LinkedHashSet<>();
        if (history == null || history.isEmpty()) {
            return uris;
        }
        for (AgentMessage message : history) {
            if (message == null || message.attachments() == null || message.attachments().isEmpty()) {
                continue;
            }
            for (MessageAttachment attachment : message.attachments()) {
                if (attachment == null || attachment.uri() == null || attachment.uri().isBlank()) {
                    continue;
                }
                if (storageService.supports(attachment.uri())) {
                    uris.add(attachment.uri());
                }
            }
        }
        return uris;
    }

    private void deleteAttachmentsQuietly(String sessionId, Set<String> attachmentUris) {
        for (String uri : attachmentUris) {
            try {
                storageService.deleteObject(uri);
            } catch (IOException | RuntimeException e) {
                log.warn("Failed to delete chat attachment session={} uri={}: {}", sessionId, uri, e.getMessage());
            }
        }
    }
}
