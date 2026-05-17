package com.echomind.console.service;

import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatResponse;
import com.echomind.common.model.MessageAttachment;
import com.echomind.console.auth.AuthContext;
import com.echomind.console.dto.ChatMessageRequest;
import com.echomind.console.dto.ChatSubmitResponse;
import com.echomind.console.dto.ChatSyncResponse;
import com.echomind.memory.MemoryManager;
import com.echomind.skill.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 聊天应用服务。
 *
 * <p>统一收口同步、异步和流式聊天的应用层逻辑；Controller 只处理 HTTP/SSE 边界，
 * RabbitMQ Consumer 只负责消息出入队。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatApplicationService {

    private final AgentOrchestrator orchestrator;
    private final ChatRabbitProducer rabbitProducer;
    private final MemoryManager memoryManager;
    private final ObjectStorageService storageService;
    private final SsePushService ssePushService;

    /** 提交异步聊天请求，立即返回 requestId 和 sessionId。 */
    public ChatSubmitResponse submitAsync(ChatMessageRequest request) {
        NormalizedChatRequest normalized = normalize(request);
        String requestId = UUID.randomUUID().toString();
        ssePushService.registerRequest(requestId, normalized.userId());
        try {
            rabbitProducer.publish(new ChatRequest(
                requestId,
                normalized.userId(),
                normalized.agentId(),
                normalized.sessionId(),
                normalized.message(),
                normalized.modelId(),
                normalized.attachments()
            ));
        } catch (RuntimeException e) {
            ssePushService.discardRequest(requestId);
            throw e;
        }
        return new ChatSubmitResponse(requestId, normalized.sessionId());
    }

    /** 执行同步聊天，直接返回完整结果。 */
    public ChatSyncResponse executeSync(ChatMessageRequest request) {
        NormalizedChatRequest n = normalize(request);
        return ChatSyncResponse.from(execute(n.userId(), n.agentId(), n.sessionId(), n.message(), n.modelId(), n.attachments()));
    }

    /** 执行队列消费者拿到的聊天请求。 */
    public ChatResponse executeQueued(ChatRequest request) {
        try {
            PipelineContext ctx = execute(request.agentId(), request.sessionId(), request.message(),
                request.modelId(), request.attachments(), request.userId());
            return ChatResponse.success(
                request.requestId(),
                ctx.getSessionId(),
                ctx.getAgentId(),
                ctx.getModelId(),
                ctx.getFinalResponse(),
                ctx.getSkillResults()
            );
        } catch (Exception e) {
            return ChatResponse.error(request.requestId(), e.getMessage());
        }
    }

    /** 准备流式聊天，返回 sessionId 与 token 流。 */
    public StreamingChat prepareStream(ChatMessageRequest request) {
        NormalizedChatRequest n = normalize(request);
        Flux<String> tokens = orchestrator.executeStream(
            n.userId(), n.agentId(), n.sessionId(), n.message(), n.modelId(), n.attachments());
        return new StreamingChat(n.userId(), n.sessionId(), tokens);
    }

    /** 删除单条普通聊天会话，并尽力回收历史消息中的附件对象。 */
    public Map<String, String> deleteSession(String sessionId) {
        validateSessionId(sessionId);
        String userId = AuthContext.userId();
        Set<String> attachmentUris = collectManagedAttachmentUris(memoryManager.getFullContext(userId, sessionId));
        memoryManager.clearSession(userId, sessionId);
        deleteAttachmentsQuietly(sessionId, attachmentUris);
        return Map.of("status", "deleted", "sessionId", sessionId);
    }

    private PipelineContext execute(String userId, String agentId, String sessionId, String message, String modelId,
                                    List<MessageAttachment> attachments) {
        return orchestrator.execute(userId, agentId, sessionId, message, modelId, attachments);
    }

    private PipelineContext execute(String agentId, String sessionId, String message, String modelId,
                                    List<MessageAttachment> attachments, String userId) {
        return execute(userId, agentId, sessionId, message, modelId, attachments);
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

    private NormalizedChatRequest normalize(ChatMessageRequest request) {
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

        return new NormalizedChatRequest(AuthContext.userId(), agentId, sessionId, message, modelId, attachments);
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
    }

    private record NormalizedChatRequest(String userId, String agentId, String sessionId, String message,
                                         String modelId, List<MessageAttachment> attachments) {}

    public record StreamingChat(String userId, String sessionId, Flux<String> tokens) {}
}
