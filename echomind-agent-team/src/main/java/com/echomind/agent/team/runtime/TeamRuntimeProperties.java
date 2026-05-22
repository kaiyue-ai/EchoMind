package com.echomind.agent.team.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent Team runtime guardrails.
 */
@Component
@ConfigurationProperties(prefix = "echomind.team.runtime")
public class TeamRuntimeProperties {

    private int maxPlanRetries = 1;
    private int maxResultReplans = 1;
    private int maxStepRetries = 2;
    private int maxReviewerFormatRepairs = 1;
    private int maxArbitrations = 1;
    private int maxConcurrentSteps = 4;
    private int stepTimeoutSeconds = 120;
    private int runTimeoutSeconds = 900;

    public int getMaxPlanRetries() {
        return maxPlanRetries;
    }

    public void setMaxPlanRetries(int maxPlanRetries) {
        this.maxPlanRetries = Math.max(0, maxPlanRetries);
    }

    public int getMaxResultReplans() {
        return maxResultReplans;
    }

    public void setMaxResultReplans(int maxResultReplans) {
        this.maxResultReplans = Math.max(0, maxResultReplans);
    }

    public int getMaxStepRetries() {
        return maxStepRetries;
    }

    public void setMaxStepRetries(int maxStepRetries) {
        this.maxStepRetries = Math.max(0, maxStepRetries);
    }

    public int getMaxReviewerFormatRepairs() {
        return maxReviewerFormatRepairs;
    }

    public void setMaxReviewerFormatRepairs(int maxReviewerFormatRepairs) {
        this.maxReviewerFormatRepairs = Math.max(0, maxReviewerFormatRepairs);
    }

    public int getMaxArbitrations() {
        return maxArbitrations;
    }

    public void setMaxArbitrations(int maxArbitrations) {
        this.maxArbitrations = Math.max(0, maxArbitrations);
    }

    public int getMaxConcurrentSteps() {
        return maxConcurrentSteps;
    }

    public void setMaxConcurrentSteps(int maxConcurrentSteps) {
        this.maxConcurrentSteps = Math.max(1, maxConcurrentSteps);
    }

    public int getStepTimeoutSeconds() {
        return stepTimeoutSeconds;
    }

    public void setStepTimeoutSeconds(int stepTimeoutSeconds) {
        this.stepTimeoutSeconds = Math.max(0, stepTimeoutSeconds);
    }

    public int getRunTimeoutSeconds() {
        return runTimeoutSeconds;
    }

    public void setRunTimeoutSeconds(int runTimeoutSeconds) {
        this.runTimeoutSeconds = Math.max(0, runTimeoutSeconds);
    }
}
