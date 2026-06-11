package com.echomind.agent.team.runtime;

/**
 * Per-run Team review switches.
 *
 * <p>The defaults intentionally preserve the existing quality-first behavior:
 * all review gates are enabled and SIMPLE fast path is opt-in.</p>
 */
public record TeamReviewOptions(
    boolean planReviewEnabled,
    boolean subReviewEnabled,
    boolean globalReviewEnabled,
    boolean simpleFastPathEnabled
) {

    public static final TeamReviewOptions QUALITY_FIRST = new TeamReviewOptions(true, true, true, false);

    public static TeamReviewOptions normalize(TeamReviewOptions options) {
        return options == null ? QUALITY_FIRST : options;
    }

    public static TeamReviewOptions fromNullable(Boolean planReviewEnabled,
                                                 Boolean subReviewEnabled,
                                                 Boolean globalReviewEnabled,
                                                 Boolean simpleFastPathEnabled) {
        return new TeamReviewOptions(
            planReviewEnabled == null || planReviewEnabled,
            subReviewEnabled == null || subReviewEnabled,
            globalReviewEnabled == null || globalReviewEnabled,
            simpleFastPathEnabled != null && simpleFastPathEnabled
        );
    }
}
