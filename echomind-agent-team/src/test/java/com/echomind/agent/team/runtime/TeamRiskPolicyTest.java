package com.echomind.agent.team.runtime;

import com.echomind.agent.team.model.PlannedStep;
import com.echomind.agent.team.state.TeamRiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TeamRiskPolicyTest {

    private final TeamRiskPolicy policy = new TeamRiskPolicy();

    @Test
    void plannerHighRiskSuggestionIsRespected() {
        TeamRiskDecision decision = policy.decide(new PlannedStep(
            "weather",
            "天气风险评估",
            "查询天气",
            List.of("weather"),
            "给出风险",
            List.of(),
            TeamRiskLevel.HIGH,
            "户外安全风险"
        ));

        assertThat(decision.level()).isEqualTo(TeamRiskLevel.HIGH);
        assertThat(decision.reason()).contains("Planner 建议高风险");
    }

    @Test
    void metadataTagCanForceHighRisk() {
        TeamRiskDecision decision = policy.decide(new PlannedStep(
            "budget",
            "费用核算",
            "核算预算",
            List.of("review-required"),
            "给出预算",
            List.of(),
            TeamRiskLevel.LOW,
            ""
        ));

        assertThat(decision.level()).isEqualTo(TeamRiskLevel.HIGH);
        assertThat(decision.reason()).contains("能力标签");
    }
}
