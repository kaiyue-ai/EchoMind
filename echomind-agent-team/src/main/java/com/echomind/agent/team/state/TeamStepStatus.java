package com.echomind.agent.team.state;

/**
 * Planner 拆出的单个 Step 的执行状态。
 */
public enum TeamStepStatus {
    PENDING, // 待开始
    BLOCKED, // 已阻塞
    READY, // 已就绪
    ASSIGNED, // 已分配
    RUNNING, // 运行中
    RETRYING, // 重试中
    COMPLETED, // 已完成
    FAILED, // 失败
    SUPERSEDED // 已替代
}
