package com.echomind.agent.team.runtime;

import com.echomind.agent.team.state.TeamStepStatus;

import java.time.Instant;
import java.util.List;

/**
 * Team Step 的接口快照。
 */
public record TeamStepSnapshot(
    String stepId,
    int stepIndex,
    String title,
    String description,
    List<String> requiredCapabilities,
    String acceptanceCriteria,
    String assignedAgentId,
    TeamStepStatus status,
    String rawOutput,
    String revisionInstructions,
    String reviewStatus,
    int retryCount,
    Instant startedAt,
    Instant completedAt
) {}
