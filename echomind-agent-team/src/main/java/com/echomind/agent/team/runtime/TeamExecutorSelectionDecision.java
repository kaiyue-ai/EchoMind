package com.echomind.agent.team.runtime;

/**
 * Planner/LLM decision for choosing one Executor member from ranked candidates.
 */
public record TeamExecutorSelectionDecision(
    String memberKey, // 选择的成员key
    String agentId, // 选择的执行器id
    String reason // 原因
) {
    public TeamExecutorSelectionDecision {
        memberKey = memberKey == null ? "" : memberKey.trim();
        agentId = agentId == null ? "" : agentId.trim();
        reason = reason == null ? "" : reason.trim();
    }
}
