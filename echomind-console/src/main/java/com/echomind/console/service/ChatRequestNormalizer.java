package com.echomind.console.service;

import com.echomind.common.model.MessageAttachment;
import com.echomind.console.auth.AuthContext;
import com.echomind.console.dto.ChatMessageRequest;

import java.util.List;
import java.util.UUID;

/**
 * Normalizes REST chat input into the application-level request shape.
 */
final class ChatRequestNormalizer {

    NormalizedChatRequest normalize(ChatMessageRequest request) {
        String message = request == null ? null : request.message();
        if (message == null || message.isBlank()) {
            boolean hasAttachments = request != null
                && request.attachments() != null
                && !request.attachments().isEmpty();
            if (hasAttachments) {
                message = "请理解这张图片。";
            } else {
                throw new IllegalArgumentException("message is required");
            }
        }

        String agentId = request == null ? null : request.agentId();
        if (agentId == null || agentId.isBlank()) {
            agentId = "default";
        }

        String sessionId = request == null ? null : request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        String modelId = request == null ? null : request.modelId();
        List<MessageAttachment> attachments = request == null || request.attachments() == null
            ? List.of()
            : request.attachments();

        var authUser = AuthContext.current();
        return new NormalizedChatRequest(authUser, authUser.userId(), agentId, sessionId, message, modelId, attachments);
    }
}
