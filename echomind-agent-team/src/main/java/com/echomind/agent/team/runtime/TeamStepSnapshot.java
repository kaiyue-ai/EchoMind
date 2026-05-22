package com.echomind.agent.team.runtime;

import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.state.TeamRiskLevel;
import com.echomind.agent.team.state.TeamStepQualityStatus;

import java.time.Instant;
import java.util.List;

/**
 * Team Step 的接口快照。
 */
public record TeamStepSnapshot(
    String stepId,
    int stepIndex,
    String clientStepId,
    String title,
    String description,
    List<String> requiredCapabilities,
    List<String> dependsOnStepIds,
    TeamRiskLevel riskLevel,
    String riskReason,
    String acceptanceCriteria,
    String assignedAgentId,
    TeamStepStatus status,
    String rawOutput,
    String revisionInstructions,
    String reviewStatus,
    TeamStepQualityStatus qualityStatus,
    String subReviewJson,
    String lastReviewReason,
    String reflectionJson,
    int planIteration,
    int retryCount,
    Instant startedAt,
    Instant completedAt
) {}
