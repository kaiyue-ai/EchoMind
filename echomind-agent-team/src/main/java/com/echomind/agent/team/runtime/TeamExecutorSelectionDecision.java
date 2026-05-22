package com.echomind.agent.team.runtime;

/**
 * Planner/LLM decision for choosing one Executor member from ranked candidates.
 */
public record TeamExecutorSelectionDecision(
    String memberKey,
    String agentId,
    String reason
) {
    public TeamExecutorSelectionDecision {
        memberKey = memberKey == null ? "" : memberKey.trim();
        agentId = agentId == null ? "" : agentId.trim();
        reason = reason == null ? "" : reason.trim();
    }
}
