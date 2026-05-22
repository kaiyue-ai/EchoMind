package com.echomind.agent.team.model;

import java.util.List;

/**
 * Step 重试时写入黑板的 Reflexion 上下文。
 */
public record StepReflection(
    List<String> failedCriteria,
    String reviewReason,
    String revisionInstructions,
    String previousOutputSummary,
    List<String> avoidRepeating
) {
    public StepReflection {
        failedCriteria = failedCriteria == null ? List.of() : List.copyOf(failedCriteria);
        reviewReason = reviewReason == null ? "" : reviewReason;
        revisionInstructions = revisionInstructions == null ? "" : revisionInstructions;
        previousOutputSummary = previousOutputSummary == null ? "" : previousOutputSummary;
        avoidRepeating = avoidRepeating == null ? List.of() : List.copyOf(avoidRepeating);
    }
}
