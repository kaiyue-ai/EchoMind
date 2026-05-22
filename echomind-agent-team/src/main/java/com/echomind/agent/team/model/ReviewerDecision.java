package com.echomind.agent.team.model;

import com.echomind.agent.team.state.ReviewerAction;

import java.util.List;
import java.util.Map;

/**
 * Reviewer 对计划或执行结果的结构化审查结论。
 */
public record ReviewerDecision(
    ReviewerAction action,
    String reason,
    List<String> questions,
    List<String> retryStepIds,
    List<String> affectedStepIds,
    String revisionInstructions,
    String finalReport,
    Map<String, StepReflection> stepReflections
) {
    public ReviewerDecision(ReviewerAction action, String reason, List<String> questions, List<String> retryStepIds,
                            String revisionInstructions, String finalReport) {
        this(action, reason, questions, retryStepIds, List.of(), revisionInstructions, finalReport, Map.of());
    }

    public ReviewerDecision {
        questions = questions == null ? List.of() : List.copyOf(questions);
        retryStepIds = retryStepIds == null ? List.of() : List.copyOf(retryStepIds);
        affectedStepIds = affectedStepIds == null ? List.of() : List.copyOf(affectedStepIds);
        revisionInstructions = revisionInstructions == null ? "" : revisionInstructions;
        finalReport = finalReport == null ? "" : finalReport;
        stepReflections = stepReflections == null ? Map.of() : Map.copyOf(stepReflections);
    }

    public static ReviewerDecision continueWith(String reason, String finalReport) {
        return new ReviewerDecision(
            ReviewerAction.CONTINUE,
            reason == null || reason.isBlank() ? "Reviewer approved" : reason,
            List.of(),
            List.of(),
            List.of(),
            "",
            finalReport == null ? "" : finalReport,
            Map.of()
        );
    }
}
