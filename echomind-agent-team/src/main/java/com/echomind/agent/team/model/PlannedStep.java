package com.echomind.agent.team.model;

import java.util.List;

/**
 * Planner 结构化拆出的子任务。
 */
public record PlannedStep(
    String title,
    String description,
    List<String> requiredCapabilities,
    String acceptanceCriteria
) {}
