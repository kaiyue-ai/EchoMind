package com.echomind.agent.team.runtime;

import com.echomind.agent.team.state.TeamRiskLevel;

/**
 * RiskPolicy output for a planned Step.
 */
public record TeamRiskDecision(
    TeamRiskLevel level,
    String reason
) {}
