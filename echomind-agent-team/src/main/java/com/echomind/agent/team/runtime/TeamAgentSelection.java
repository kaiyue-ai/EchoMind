package com.echomind.agent.team.runtime;

import java.util.List;

/**
 * Decision record for Executor selection.
 */
public record TeamAgentSelection(
    String memberKey, //成员的key
    String agentId, // agent的id
    String agentName, // agent 名称
    List<String> capabilityTags, // 能力标签
    int sortOrder, // 分类
    int matchScore, //匹配分数
    String decisionSource, // 决策来源
    String reason //原因
) {
    public TeamAgentSelection {
        capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
        decisionSource = decisionSource == null || decisionSource.isBlank() ? "RULE_FALLBACK" : decisionSource;
        reason = reason == null ? "" : reason;
    }

    public TeamAgentSelection withDecision(String source, String decisionReason) {
        return new TeamAgentSelection(memberKey, agentId, agentName, capabilityTags, sortOrder, matchScore, source,
            decisionReason == null || decisionReason.isBlank() ? reason : decisionReason);
    }
}
