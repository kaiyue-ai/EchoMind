package com.echomind.agent.team.state;

/**
 * 一次团队协作 Run 的状态机节点。
 */
public enum TeamRunStatus {
    PENDING,
    PLANNING,
    PLAN_REVIEWING,
    EXECUTING,
    MERGING,
    GLOBAL_REVIEWING,
    NEEDS_CLARIFICATION,
    COMPLETED,
    FAILED
}
