package com.echomind.agent.team.runtime;

import java.util.List;

/**
 * Structured conflict detection result after MergeAgent.
 */
public record TeamConflictReport(
    boolean hasConflict,
    List<String> conflictFields,
    List<String> affectedStepIds,
    String reason,
    String normalizationAdvice
) {
    public static TeamConflictReport none(String reason) {
        return new TeamConflictReport(false, List.of(), List.of(), reason == null ? "" : reason, "");
    }
}
