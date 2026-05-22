package com.echomind.console.service;

import com.echomind.common.model.MessageAttachment;
import com.echomind.console.auth.AuthUser;

import java.util.List;

record NormalizedChatRequest(AuthUser authUser, String userId, String agentId, String sessionId, String message,
                             String modelId, List<MessageAttachment> attachments) {
    NormalizedChatRequest withMessage(String nextMessage) {
        return new NormalizedChatRequest(authUser, userId, agentId, sessionId, nextMessage, modelId, attachments);
    }
}
