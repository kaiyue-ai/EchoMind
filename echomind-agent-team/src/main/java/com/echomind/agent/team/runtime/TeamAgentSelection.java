package com.echomind.agent.team.runtime;

import java.util.List;

/**
 * Decision record for Executor selection.
 */
public record TeamAgentSelection(
    String memberKey,
    String agentId,
    String agentName,
    List<String> capabilityTags,
    int sortOrder,
    int capabilityScore,
    int loadScore,
    int healthScore,
    int totalScore,
    String healthStatus,
    String decisionSource,
    String reason
) {
    public TeamAgentSelection(String agentId, String agentName, int capabilityScore, int loadScore, int healthScore,
                              int totalScore, String healthStatus, String reason) {
        this(agentId, agentId, agentName, List.of(), 0, capabilityScore, loadScore, healthScore, totalScore,
            healthStatus, "RULE_FALLBACK", reason);
    }

    public TeamAgentSelection {
        capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
        decisionSource = decisionSource == null || decisionSource.isBlank() ? "RULE_FALLBACK" : decisionSource;
        reason = reason == null ? "" : reason;
    }

    public TeamAgentSelection withDecision(String source, String decisionReason) {
        return new TeamAgentSelection(memberKey, agentId, agentName, capabilityTags, sortOrder, capabilityScore,
            loadScore, healthScore, totalScore, healthStatus, source,
            decisionReason == null || decisionReason.isBlank() ? reason : decisionReason);
    }
}
