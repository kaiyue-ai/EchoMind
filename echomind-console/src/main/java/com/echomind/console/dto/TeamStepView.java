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
    String title,
    String description,
    List<String> requiredCapabilities,
    String acceptanceCriteria,
    String assignedAgentId,
    String status,
    String rawOutput,
    String revisionInstructions,
    String reviewStatus,
    int retryCount,
    Instant startedAt,
    Instant completedAt
) {
    public static TeamStepView from(TeamStepSnapshot step) {
        return new TeamStepView(
            step.stepId(),
            step.stepIndex(),
            step.title(),
            step.description(),
            step.requiredCapabilities(),
            step.acceptanceCriteria(),
            step.assignedAgentId(),
            step.status().name(),
            step.rawOutput(),
            step.revisionInstructions(),
            step.reviewStatus(),
            step.retryCount(),
            step.startedAt(),
            step.completedAt()
        );
    }
}
