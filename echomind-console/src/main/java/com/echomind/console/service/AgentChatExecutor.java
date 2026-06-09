package com.echomind.console.service;

import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.orchestration.AgentOrchestrator.StreamExecution;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.MessageAttachment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Small adapter around AgentOrchestrator that preserves the raw user message when present.
 */
@Service
@RequiredArgsConstructor
class AgentChatExecutor {

    private final AgentOrchestrator orchestrator;

    PipelineContext execute(String userId, String agentId, String sessionId, String message, String modelId,
                            List<MessageAttachment> attachments, String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return orchestrator.execute(userId, agentId, sessionId, message, modelId, attachments);
        }
        return orchestrator.execute(userId, agentId, sessionId, message, modelId, attachments, rawMessage);
    }

    // 执行流式聊天，返回流式聊天上下文
    Mono<StreamExecution> executeStream(String userId, String agentId, String sessionId, String message,
                                        String modelId, List<MessageAttachment> attachments, String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) { // 没有原始消息
            // 承接执行流式聊天上下文
            return orchestrator.executeStreamContext(userId, agentId, sessionId, message, modelId, attachments);
        }
        return orchestrator.executeStreamContext(userId, agentId, sessionId, message, modelId, attachments, rawMessage);
    }

    Mono<StreamExecution> executeStream(String userId, String agentId, String sessionId, String message,
                                        String modelId, List<MessageAttachment> attachments, String rawMessage,
                                        Map<String, Object> initialAttributes) {
        if (initialAttributes == null || initialAttributes.isEmpty()) {
            return executeStream(userId, agentId, sessionId, message, modelId, attachments, rawMessage);
        }
        if (rawMessage == null || rawMessage.isBlank()) { // 没有原始消息
            // 扴接执行流式聊天上下文
            return orchestrator.executeStreamContext(userId, agentId, sessionId, message, modelId, attachments,
                null, initialAttributes);
        }
        return orchestrator.executeStreamContext(userId, agentId, sessionId, message, modelId, attachments, rawMessage,
            initialAttributes);
    }
}
