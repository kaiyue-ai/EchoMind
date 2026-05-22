package com.echomind.agent.team.state;

/**
 * Planner 拆出的单个 Step 的执行状态。
 */
public enum TeamStepStatus {
    PENDING,
    BLOCKED,
    READY,
    ASSIGNED,
    RUNNING,
    RETRYING,
    COMPLETED,
    FAILED,
    SUPERSEDED
}
