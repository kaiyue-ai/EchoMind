package com.echomind.agent.team.model;

import com.echomind.agent.team.state.TeamRiskLevel;

import java.util.List;

/**
 * Planner 结构化拆出的子任务。
 */
public record PlannedStep(
    String clientStepId, // 客户步骤ID
    String title, // 标题
    String description, // 描述
    List<String> requiredCapabilities, //需要的tool
    String acceptanceCriteria, // 接受标准
    List<String> dependsOn, // 依赖的步骤ID
    TeamRiskLevel riskLevel, // 风险等级
    String riskReason // 风险原因
) {
    public PlannedStep(String title, String description, List<String> requiredCapabilities,
                       String acceptanceCriteria) {
        this(null, title, description, requiredCapabilities, acceptanceCriteria, List.of(), TeamRiskLevel.LOW, "");
    }

    public PlannedStep {
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        riskLevel = riskLevel == null ? TeamRiskLevel.LOW : riskLevel;
        riskReason = riskReason == null ? "" : riskReason;
    }
}
