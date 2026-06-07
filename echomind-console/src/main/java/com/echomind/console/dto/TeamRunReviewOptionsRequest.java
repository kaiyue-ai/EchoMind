package com.echomind.console.dto;

import com.echomind.agent.team.runtime.TeamReviewOptions;

/**
 * Optional per-run Team review switches supplied by the client.
 */
public record TeamRunReviewOptionsRequest(
    Boolean planReviewEnabled,
    Boolean subReviewEnabled,
    Boolean globalReviewEnabled,
    Boolean simpleFastPathEnabled
) {

    public TeamReviewOptions toRuntimeOptions() {
        return TeamReviewOptions.fromNullable(
            planReviewEnabled,
            subReviewEnabled,
            globalReviewEnabled,
            simpleFastPathEnabled
        );
    }
}
