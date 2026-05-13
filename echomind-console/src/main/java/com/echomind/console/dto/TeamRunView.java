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
    String task,
    String status,
    String clarificationQuestion,
    String clarificationAnswer,
    String clarificationStage,
    String planReviewJson,
    String resultReviewJson,
    String finalOutput,
    String mermaidDiagram,
    int planRetryCount,
    List<TeamStepView> steps,
    List<TeamEventView> events,
    Instant createdAt,
    Instant updatedAt
) {
    public static TeamRunView from(TeamRunSnapshot run) {
        return new TeamRunView(
            run.runId(),
            run.teamId(),
            run.task(),
            run.status().name(),
            run.clarificationQuestion(),
            run.clarificationAnswer(),
            run.clarificationStage(),
            run.planReviewJson(),
            run.resultReviewJson(),
            run.finalOutput(),
            run.mermaidDiagram(),
            run.planRetryCount(),
            run.steps().stream().map(TeamStepView::from).toList(),
            run.events().stream().map(TeamEventView::from).toList(),
            run.createdAt(),
            run.updatedAt()
        );
    }
}
