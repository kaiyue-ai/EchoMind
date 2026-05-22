package com.echomind.console.dto;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.TokenUsage;

import java.util.List;

/**
 * 同步聊天的完整响应。
 */
public record ChatSyncResponse(
    String sessionId,
    String agentId,
    String modelId,
    String traceId,
    String response,
    List<String> skillResults,
    TokenUsage tokenUsage
) {
    public static ChatSyncResponse from(PipelineContext ctx) {
        return new ChatSyncResponse(
            ctx.getSessionId(),
            ctx.getAgentId(),
            ctx.getModelId(),
            ctx.getTraceId(),
            ctx.getFinalResponse(),
            List.copyOf(ctx.getSkillResults()),
            ctx.getTokenUsage()
        );
    }
}
