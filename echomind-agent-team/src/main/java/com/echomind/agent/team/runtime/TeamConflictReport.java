package com.echomind.agent.team.runtime;

import java.util.List;

/**
 * 这是 ConflictDetector 检测到的冲突报告，告诉系统各个 Executor 的结果之间有没有矛盾！
 */
public record TeamConflictReport(
    boolean hasConflict, // 有矛盾
    List<String> conflictFields, //矛盾点
    List<String> affectedStepIds, // 影响的步骤
    String reason, // 原因
    String normalizationAdvice //正常建议
) {
    public static TeamConflictReport none(String reason) {
        return new TeamConflictReport(false, List.of(), List.of(), reason == null ? "" : reason, "");
    }
}
