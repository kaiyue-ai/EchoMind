package com.echomind.agent.team.state;

/**
 * Reviewer 对计划或执行结果的结构化决策。
 */
public enum ReviewerAction {
    CONTINUE,
    RETRY,
    PARTIAL_REPLAN,
    REPLAN,
    ASK_CLARIFICATION,
    FAILED
}
