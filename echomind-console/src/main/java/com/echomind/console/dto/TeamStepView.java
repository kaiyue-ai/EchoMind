package com.echomind.console.dto;

import com.echomind.agent.team.runtime.TeamStepSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * Team Step 看板视图。
 */
public record TeamStepView(
    String stepId,
    int stepIndex,
    String clientStepId,
    String title,
    String description,
    List<String> requiredCapabilities,
    List<String> dependsOnStepIds,
    String riskLevel,
    String riskReason,
    String acceptanceCriteria,
    String assignedAgentId,
    String status,
    String rawOutput,
    String revisionInstructions,
    String reviewStatus,
    String qualityStatus,
    String subReviewJson,
    String lastReviewReason,
    String reflectionJson,
    int planIteration,
    int retryCount,
    Instant startedAt,
    Instant completedAt
) {
    public static TeamStepView from(TeamStepSnapshot step) {
        return new TeamStepView(
            step.stepId(),
            step.stepIndex(),
            step.clientStepId(),
            step.title(),
            step.description(),
            step.requiredCapabilities(),
            step.dependsOnStepIds(),
            step.riskLevel() == null ? null : step.riskLevel().name(),
            step.riskReason(),
            step.acceptanceCriteria(),
            step.assignedAgentId(),
            step.status().name(),
            step.rawOutput(),
            step.revisionInstructions(),
            step.reviewStatus(),
            step.qualityStatus() == null ? null : step.qualityStatus().name(),
            step.subReviewJson(),
            step.lastReviewReason(),
            step.reflectionJson(),
            step.planIteration(),
            step.retryCount(),
            step.startedAt(),
            step.completedAt()
        );
    }
}
