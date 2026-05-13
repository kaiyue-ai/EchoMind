package com.echomind.console.dto;

import com.echomind.agent.team.TeamResult;
import com.echomind.agent.team.runtime.TeamRunSnapshot;

import java.util.List;

/**
 * Agent Team 执行结果的接口视图。
 */
public record TeamExecutionResponse(
    String teamId,
    String status,
    String finalOutput,
    List<String> stepResults,
    String mermaidDiagram
) {
    public static TeamExecutionResponse from(String teamId, TeamResult result) {
        return new TeamExecutionResponse(
            teamId,
            result.status(),
            result.finalOutput(),
            result.stepResults(),
            result.mermaidDiagram()
        );
    }

    public static TeamExecutionResponse from(String teamId, TeamRunSnapshot result) {
        return new TeamExecutionResponse(
            teamId,
            result.status().name(),
            result.finalOutput(),
            result.steps().stream()
                .map(step -> step.rawOutput() == null ? "" : step.rawOutput())
                .toList(),
            result.mermaidDiagram()
        );
    }
}
