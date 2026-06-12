package com.echomind.agent.team.state;

/**
 * Reviewer 对计划或执行结果的结构化决策。
 */
public enum ReviewerAction {
    CONTINUE, // 继续
    SUCCESS, // 终审成功
    RETRY, // 重试
    REWORK, // Step 返工
    PASS, // Step 通过
    ACCEPT_WITH_RISK, // Step 带风险放行
    REMERGE, // 终审要求重新聚合
    PARTIAL_REPLAN, // 部分重新计划
    REPLAN, // 重新计划
    FAILED // 失败
}
