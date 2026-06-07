package com.echomind.agent.team.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeamRuntimePropertiesTest {

    @Test
    void defaultsStayConservativeForInteractiveRuns() {
        TeamRuntimeProperties properties = new TeamRuntimeProperties();

        assertThat(properties.getMaxPlanRetries()).isEqualTo(2);
        assertThat(properties.getMaxResultReplans()).isEqualTo(2);
        assertThat(properties.getMaxStepRetries()).isEqualTo(2);
        assertThat(properties.getMaxReviewerFormatRepairs()).isEqualTo(3);
        assertThat(properties.getMaxArbitrations()).isEqualTo(2);
        assertThat(properties.getMaxConcurrentSteps()).isEqualTo(7);
        assertThat(properties.getStepTimeoutSeconds()).isEqualTo(180);
        assertThat(properties.getRunTimeoutSeconds()).isEqualTo(1200);
    }

    @Test
    void externalValuesAreClampedToRuntimeGuardrails() {
        TeamRuntimeProperties properties = new TeamRuntimeProperties();

        properties.setMaxPlanRetries(20);
        properties.setMaxResultReplans(20);
        properties.setMaxStepRetries(20);
        properties.setMaxReviewerFormatRepairs(20);
        properties.setMaxArbitrations(20);
        properties.setMaxConcurrentSteps(20);
        properties.setStepTimeoutSeconds(20_000);
        properties.setRunTimeoutSeconds(20_000);

        assertThat(properties.getMaxPlanRetries()).isEqualTo(3);
        assertThat(properties.getMaxResultReplans()).isEqualTo(2);
        assertThat(properties.getMaxStepRetries()).isEqualTo(3);
        assertThat(properties.getMaxReviewerFormatRepairs()).isEqualTo(3);
        assertThat(properties.getMaxArbitrations()).isEqualTo(2);
        assertThat(properties.getMaxConcurrentSteps()).isEqualTo(8);
        assertThat(properties.getStepTimeoutSeconds()).isEqualTo(600);
        assertThat(properties.getRunTimeoutSeconds()).isEqualTo(7200);
    }

    @Test
    void invalidLowValuesDisableRetriesButKeepAtLeastOneConcurrentStep() {
        TeamRuntimeProperties properties = new TeamRuntimeProperties();

        properties.setMaxPlanRetries(-1);
        properties.setMaxResultReplans(-1);
        properties.setMaxStepRetries(-1);
        properties.setMaxReviewerFormatRepairs(-1);
        properties.setMaxArbitrations(-1);
        properties.setMaxConcurrentSteps(-1);
        properties.setStepTimeoutSeconds(-1);
        properties.setRunTimeoutSeconds(-1);

        assertThat(properties.getMaxPlanRetries()).isZero();
        assertThat(properties.getMaxResultReplans()).isZero();
        assertThat(properties.getMaxStepRetries()).isZero();
        assertThat(properties.getMaxReviewerFormatRepairs()).isZero();
        assertThat(properties.getMaxArbitrations()).isZero();
        assertThat(properties.getMaxConcurrentSteps()).isEqualTo(1);
        assertThat(properties.getStepTimeoutSeconds()).isZero();
        assertThat(properties.getRunTimeoutSeconds()).isZero();
    }
}
