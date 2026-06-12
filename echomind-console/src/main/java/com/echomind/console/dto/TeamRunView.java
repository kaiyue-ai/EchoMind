package com.echomind.console.dto;

import com.echomind.agent.team.runtime.TeamRunSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * Team Run 黑板完整视图。
 */
public record TeamRunView(
    String runId,
    String teamId,
    String userId,
    String task,
    String status,
    String taskLevel,
    TeamRunReviewOptionsView reviewOptions,
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
    List<TeamStepView> steps,
    List<TeamEventView> events,
    Instant createdAt,
    Instant updatedAt
) {
    public static TeamRunView from(TeamRunSnapshot run) {
        return new TeamRunView(
            run.runId(),
            run.teamId(),
            run.userId(),
            run.task(),
            run.status().name(),
            run.taskLevel(),
            TeamRunReviewOptionsView.from(run.reviewOptions()),
            run.planReviewJson(),
            run.resultReviewJson(),
            run.mergeOutput(),
            run.globalReviewJson(),
            run.conflictReportJson(),
            run.arbitrationJson(),
            run.finalOutput(),
            run.mermaidDiagram(),
            run.planRetryCount(),
            run.resultReplanCount(),
            run.partialReplanCount(),
            run.fullReplanCount(),
            run.arbitrationCount(),
            run.steps().stream().map(TeamStepView::from).toList(),
            run.events().stream().map(TeamEventView::from).toList(),
            run.createdAt(),
            run.updatedAt()
        );
    }
}
