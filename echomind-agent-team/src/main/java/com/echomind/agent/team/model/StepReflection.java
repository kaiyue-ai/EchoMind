package com.echomind.agent.team.model;

import java.util.List;

/**
 * Step 重试时写入黑板的 Reflexion 上下文。
 */
public record StepReflection(
    List<String> failedCriteria, // 没满足什么验收标准
    String reviewReason, // 原因
    String revisionInstructions, // 这一次怎么改动
    String previousOutputSummary, // 上一次输出的摘要
    List<String> avoidRepeating // 别再犯哪些错误
) {
    public StepReflection {
        failedCriteria = failedCriteria == null ? List.of() : List.copyOf(failedCriteria);
        reviewReason = reviewReason == null ? "" : reviewReason;
        revisionInstructions = revisionInstructions == null ? "" : revisionInstructions;
        previousOutputSummary = previousOutputSummary == null ? "" : previousOutputSummary;
        avoidRepeating = avoidRepeating == null ? List.of() : List.copyOf(avoidRepeating);
    }
}
