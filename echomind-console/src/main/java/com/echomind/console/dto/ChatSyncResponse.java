package com.echomind.console.dto;

import com.echomind.agent.pipeline.PipelineContext;

import java.util.List;

/**
 * 同步聊天的完整响应。
 */
public record ChatSyncResponse(
    String sessionId,
    String agentId,
    String modelId,
    String response,
    List<String> skillResults
) {
    public static ChatSyncResponse from(PipelineContext ctx) {
        return new ChatSyncResponse(
            ctx.getSessionId(),
            ctx.getAgentId(),
            ctx.getModelId(),
            ctx.getFinalResponse(),
            List.copyOf(ctx.getSkillResults())
        );
    }
}
