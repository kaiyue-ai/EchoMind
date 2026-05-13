package com.echomind.console.service;

import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatResponse;
import com.echomind.common.model.MessageAttachment;
import com.echomind.console.dto.ChatMessageRequest;
import com.echomind.console.dto.ChatSubmitResponse;
import com.echomind.console.dto.ChatSyncResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.List;

/**
 * 聊天应用服务。
 *
 * <p>统一收口同步、异步和流式聊天的应用层逻辑；Controller 只处理 HTTP/SSE 边界，
 * RabbitMQ Consumer 只负责消息出入队。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatApplicationService {

    private final AgentOrchestrator orchestrator;
    private final ChatRabbitProducer rabbitProducer;

    /** 提交异步聊天请求，立即返回 requestId 和 sessionId。 */
    public ChatSubmitResponse submitAsync(ChatMessageRequest request) {
        NormalizedChatRequest normalized = normalize(request);
        String requestId = UUID.randomUUID().toString();
        rabbitProducer.publish(new ChatRequest(
            requestId,
            normalized.agentId(),
            normalized.sessionId(),
            normalized.message(),
            normalized.modelId(),
            normalized.attachments()
        ));
        return new ChatSubmitResponse(requestId, normalized.sessionId());
    }

    /** 执行同步聊天，直接返回完整结果。 */
    public ChatSyncResponse executeSync(ChatMessageRequest request) {
        NormalizedChatRequest n = normalize(request);
        return ChatSyncResponse.from(execute(n.agentId(), n.sessionId(), n.message(), n.modelId(), n.attachments()));
    }

    /** 执行队列消费者拿到的聊天请求。 */
    public ChatResponse executeQueued(ChatRequest request) {
        try {
            PipelineContext ctx = execute(request.agentId(), request.sessionId(), request.message(),
                request.modelId(), request.attachments());
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
            n.agentId(), n.sessionId(), n.message(), n.modelId(), n.attachments());
        return new StreamingChat(n.sessionId(), tokens);
    }

    private PipelineContext execute(String agentId, String sessionId, String message, String modelId,
                                    List<MessageAttachment> attachments) {
        return orchestrator.execute(agentId, sessionId, message, modelId, attachments);
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

        String agentId = request.agentId();
        if (agentId == null || agentId.isBlank()) {
            agentId = "default";
        }

        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        String modelId = request.modelId();
        List<MessageAttachment> attachments = request.attachments() == null
            ? List.of()
            : request.attachments();

        return new NormalizedChatRequest(agentId, sessionId, message, modelId, attachments);
    }

    private record NormalizedChatRequest(String agentId, String sessionId, String message,
                                         String modelId, List<MessageAttachment> attachments) {}

    public record StreamingChat(String sessionId, Flux<String> tokens) {}
}
