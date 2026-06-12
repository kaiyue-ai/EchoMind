package com.echomind.agent.team.state;

/**
 * 写入 Team 黑板事件流的事件类型。
 */
public enum TeamEventType {
    RUN_CREATED, // 运行创建
    PLAN_STARTED, // 计划开始
    PLAN_CREATED, // 计划创建
    PLAN_REVIEW_STARTED, // 计划审核开始
    PLAN_REVIEWED, // 计划审核完成
    TEAM_CONTROL_STARTED, // 团队控制开始
    AGENT_SELECTED, // 代理选择
    RISK_DECIDED, // 风险决策
    STEP_ASSIGNED, // 步骤分配
    STEP_STARTED, // 步骤开始
    STEP_COMPLETED, // 步骤完成
    STEP_FAILED, // 步骤失败
    STEP_BLOCKED, //步骤堵塞
    STEP_READY, // 步骤准备
    STEP_SUB_REVIEW_STARTED, // 步骤审批开始
    STEP_SUB_REVIEWED, // 步骤自审批
    STEP_REFLECTION_RECORDED, // 步骤反思记录
    RETRY_REQUESTED, // 重试请求
    REPLAN_REQUESTED, // 重新计划请求
    STEP_RETRY_STARTED, // 步骤重试开始
    STEP_RETRY_COMPLETED, // 步骤重试完成
    MERGE_STARTED, // 聚合开始
    MERGE_COMPLETED, // 聚合完成
    CONFLICT_DETECTED, // 冲突检测
    ARBITRATION_STARTED, // 仲裁开始
    ARBITRATION_COMPLETED, // 仲裁完成
    GLOBAL_REVIEW_STARTED, // 全局审核开始
    GLOBAL_REVIEWED, // 全局审核完成
    STEP_TIMEOUT, // 步骤超时
    RUN_TIMEOUT, // 运行超时
    RUN_COMPLETED, // 运行完成
    RUN_FAILED // 运行失败
}
