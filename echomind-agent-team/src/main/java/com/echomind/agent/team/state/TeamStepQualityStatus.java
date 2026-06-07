package com.echomind.agent.team.state;

/**
 * Step 质量闸门状态。
 */
public enum TeamStepQualityStatus {
    PENDING,
    PASSED,
    RETRY_REQUESTED,
    REVIEW_SKIPPED,
    FLAWED_ACCEPTED // 微瑕
}
