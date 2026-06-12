package com.echomind.agent.team.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Team 模块的运行时保护开关，防止无限重试、过度并发和长时间悬挂。
 */
@Component
@ConfigurationProperties(prefix = "echomind.team.runtime")
public class TeamRuntimeProperties {

    private static final int MAX_RETRY_LIMIT = 3;
    private static final int MAX_ARBITRATION_LIMIT = 2;
    private static final int MAX_MERGE_ATTEMPT_LIMIT = 3;
    private static final int MAX_CONCURRENT_STEP_LIMIT = 8;
    private static final int MAX_STEP_TIMEOUT_SECONDS = 600;
    private static final int MAX_RUN_TIMEOUT_SECONDS = 7200;

    private int maxPlanRetries = 2;
    private int maxStepRetries = 2;
    private int maxReviewerFormatRepairs = 3;
    private int maxArbitrations = 2;
    private int maxMergeAttempts = 2;
    private int maxConcurrentSteps = 7;
    private int stepTimeoutSeconds = 180;
    private int runTimeoutSeconds = 1200;
    private int dagEventShards = 16;
    private int stepExecuteConsumers = 10;
    private int maxRetryRequeues = 5;

    public int getMaxPlanRetries() {
        return maxPlanRetries;
    }

    public void setMaxPlanRetries(int maxPlanRetries) {
        this.maxPlanRetries = clamp(maxPlanRetries, 0, MAX_RETRY_LIMIT);
    }

    public int getMaxStepRetries() {
        return maxStepRetries;
    }

    public void setMaxStepRetries(int maxStepRetries) {
        this.maxStepRetries = clamp(maxStepRetries, 0, MAX_RETRY_LIMIT);
    }

    public int getMaxReviewerFormatRepairs() {
        return maxReviewerFormatRepairs;
    }

    public void setMaxReviewerFormatRepairs(int maxReviewerFormatRepairs) {
        this.maxReviewerFormatRepairs = clamp(maxReviewerFormatRepairs, 0, MAX_RETRY_LIMIT);
    }

    public int getMaxArbitrations() {
        return maxArbitrations;
    }

    public void setMaxArbitrations(int maxArbitrations) {
        this.maxArbitrations = clamp(maxArbitrations, 0, MAX_ARBITRATION_LIMIT);
    }

    public int getMaxMergeAttempts() {
        return maxMergeAttempts;
    }

    public void setMaxMergeAttempts(int maxMergeAttempts) {
        this.maxMergeAttempts = clamp(maxMergeAttempts, 0, MAX_MERGE_ATTEMPT_LIMIT);
    }

    public int getMaxConcurrentSteps() {
        return maxConcurrentSteps;
    }

    public void setMaxConcurrentSteps(int maxConcurrentSteps) {
        this.maxConcurrentSteps = clamp(maxConcurrentSteps, 1, MAX_CONCURRENT_STEP_LIMIT);
    }

    public int getStepTimeoutSeconds() {
        return stepTimeoutSeconds;
    }

    public void setStepTimeoutSeconds(int stepTimeoutSeconds) {
        this.stepTimeoutSeconds = clamp(stepTimeoutSeconds, 0, MAX_STEP_TIMEOUT_SECONDS);
    }

    public int getRunTimeoutSeconds() {
        return runTimeoutSeconds;
    }

    public void setRunTimeoutSeconds(int runTimeoutSeconds) {
        this.runTimeoutSeconds = clamp(runTimeoutSeconds, 0, MAX_RUN_TIMEOUT_SECONDS);
    }

    public int getDagEventShards() {
        return dagEventShards;
    }

    public void setDagEventShards(int dagEventShards) {
        this.dagEventShards = clamp(dagEventShards, 1, 16);
    }

    public int getStepExecuteConsumers() {
        return stepExecuteConsumers;
    }

    public void setStepExecuteConsumers(int stepExecuteConsumers) {
        this.stepExecuteConsumers = clamp(stepExecuteConsumers, 1, 50);
    }

    public int getMaxRetryRequeues() {
        return maxRetryRequeues;
    }

    public void setMaxRetryRequeues(int maxRetryRequeues) {
        this.maxRetryRequeues = clamp(maxRetryRequeues, 1, 20);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
