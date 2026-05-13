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
    String task,
    TeamRunStatus status,
    String clarificationQuestion,
    String clarificationAnswer,
    String clarificationStage,
    String planReviewJson,
    String resultReviewJson,
    String finalOutput,
    String mermaidDiagram,
    int planRetryCount,
    List<TeamStepSnapshot> steps,
    List<TeamEventSnapshot> events,
    Instant createdAt,
    Instant updatedAt
) {}
