package com.echomind.agent.team.model;

import com.echomind.agent.team.state.TeamRiskLevel;

import java.util.List;

/**
 * Planner 结构化拆出的子任务。
 */
public record PlannedStep(
    String clientStepId,
    String title,
    String description,
    List<String> requiredCapabilities,
    String acceptanceCriteria,
    List<String> dependsOn,
    TeamRiskLevel riskLevel,
    String riskReason
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
