package com.echomind.console.dto;

import com.echomind.agent.team.runtime.TeamReviewOptions;

/**
 * Review switches persisted with a Team Run.
 */
public record TeamRunReviewOptionsView(
    boolean planReviewEnabled,
    boolean subReviewEnabled,
    boolean globalReviewEnabled,
    boolean simpleFastPathEnabled
) {

    public static TeamRunReviewOptionsView from(TeamReviewOptions options) {
        TeamReviewOptions normalized = TeamReviewOptions.normalize(options);
        return new TeamRunReviewOptionsView(
            normalized.planReviewEnabled(),
            normalized.subReviewEnabled(),
            normalized.globalReviewEnabled(),
            normalized.simpleFastPathEnabled()
        );
    }
}
