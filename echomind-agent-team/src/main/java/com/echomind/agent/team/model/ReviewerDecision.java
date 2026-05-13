package com.echomind.agent.team.model;

import com.echomind.agent.team.state.ReviewerAction;

import java.util.List;

/**
 * Reviewer 对计划或执行结果的结构化审查结论。
 */
public record ReviewerDecision(
    ReviewerAction action,
    String reason,
    List<String> questions,
    List<String> retryStepIds,
    String revisionInstructions,
    String finalReport
) {
    public static ReviewerDecision continueWith(String reason, String finalReport) {
        return new ReviewerDecision(
            ReviewerAction.CONTINUE,
            reason == null || reason.isBlank() ? "Reviewer approved" : reason,
            List.of(),
            List.of(),
            "",
            finalReport == null ? "" : finalReport
        );
    }
}
