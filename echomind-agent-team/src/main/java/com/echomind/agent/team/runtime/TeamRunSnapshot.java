package com.echomind.agent.team.runtime;

import com.echomind.agent.team.state.TeamRunStatus;

import java.time.Instant;
import java.util.List;

/**
 * 一次 Team Run 的完整读取视图。
 */
public record TeamRunSnapshot(
    String runId, // �行id
    String teamId, // 团队id
    String userId, // 用户id
    String task, // 任务
    TeamRunStatus status, // 状态
    String taskLevel, // 任务等级
    TeamReviewOptions reviewOptions, // 审查策略
    String clarificationQuestion, // 澄清问题
    String clarificationAnswer, //澄清答案
    String clarificationStage, // 澄清阶段
    String planReviewJson, // 计划审查json
    String resultReviewJson, // �果审查json
    String mergeOutput, // 合并输出
    String globalReviewJson, // 全局审查json
    String conflictReportJson, // 冲突报告json
    String arbitrationJson, // 调仲裁json
    String finalOutput, // 最终输出
    String mermaidDiagram, // mermaid图
    int planRetryCount, // 计划重试次数
    int resultReplanCount, // 结果重计划次数
    int partialReplanCount, // 部分重计划次数
    int fullReplanCount, // 全重计划次数
    int arbitrationCount, // 调仲裁次数
    List<TeamStepSnapshot> steps, // 步骤快照列表
    List<TeamEventSnapshot> events, // 事件快照列表
    Instant createdAt, // 创建时间
    Instant updatedAt // 更新时间
) {}
