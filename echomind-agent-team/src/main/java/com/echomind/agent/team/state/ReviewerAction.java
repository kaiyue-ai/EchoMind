package com.echomind.agent.team.state;

/**
 * Reviewer 对计划或执行结果的结构化决策。
 */
public enum ReviewerAction {
    CONTINUE, // 继续
    RETRY, // 重试
    PARTIAL_REPLAN, // 部分重新计划
    REPLAN, // 重新计划
    ASK_CLARIFICATION, // 要求补充信息
    FAILED // 失败
}
