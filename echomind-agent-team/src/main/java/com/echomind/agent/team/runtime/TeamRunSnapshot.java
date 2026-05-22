package com.echomind.agent.team.runtime;

import com.echomind.agent.team.state.TeamRunStatus;

import java.time.Instant;
import java.util.List;

/**
 * 一次 Team Run 的完整读取视图。
 */
public record TeamRunSnapshot(
    String runId,
    String teamId,
    String userId,
    String task,
    TeamRunStatus status,
    String taskLevel,
    String clarificationQuestion,
    String clarificationAnswer,
    String clarificationStage,
    String planReviewJson,
    String resultReviewJson,
    String mergeOutput,
    String globalReviewJson,
    String conflictReportJson,
    String arbitrationJson,
    String finalOutput,
    String mermaidDiagram,
    int planRetryCount,
    int resultReplanCount,
    int partialReplanCount,
    int fullReplanCount,
    int arbitrationCount,
    List<TeamStepSnapshot> steps,
    List<TeamEventSnapshot> events,
    Instant createdAt,
    Instant updatedAt
) {}
