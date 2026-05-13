package com.echomind.agent.team.runtime;

import com.echomind.agent.team.state.ReviewerAction;
import com.echomind.agent.team.state.TeamStepStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeamJsonSupportTest {

    private final TeamJsonSupport json = new TeamJsonSupport();

    @Test
    void parseDecisionRejectsNonJsonReviewerOutput() {
        assertThatThrownBy(() -> json.parseDecision("looks fine, continue"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Reviewer response must be valid JSON");
    }

    @Test
    void parsePlanRejectsLlmErrorText() {
        assertThatThrownBy(() -> json.parsePlan("[Error] timeout"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Planner response is an error");
    }

    @Test
    void parseDecisionRejectsMissingAction() {
        assertThatThrownBy(() -> json.parseDecision("{\"reason\":\"ok\"}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Reviewer action is required");
    }

    @Test
    void parseDecisionRejectsNonExactAction() {
        assertThatThrownBy(() -> json.parseDecision("{\"action\":\"NOT_CONTINUE\",\"reason\":\"bad\"}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown reviewer action");
    }

    @Test
    void parseDecisionReturnsStructuredAction() {
        var decision = json.parseDecision("""
            {"action":"RETRY","reason":"missing weather","retryStepIds":["step-1"],"revisionInstructions":"补天气风险"}
            """);

        assertThat(decision.action()).isEqualTo(ReviewerAction.RETRY);
        assertThat(decision.retryStepIds()).containsExactly("step-1");
        assertThat(decision.revisionInstructions()).contains("天气");
    }

    @Test
    void serializesTeamSnapshotsWithJavaTimeFields() {
        String value = json.toJson(List.of(new TeamStepSnapshot(
            "step-1",
            1,
            "准备议程",
            "整理技术分享会议程",
            List.of("general"),
            "输出可执行议程",
            "default",
            TeamStepStatus.COMPLETED,
            "议程完成",
            "",
            "",
            0,
            Instant.parse("2026-05-12T12:00:00Z"),
            Instant.parse("2026-05-12T12:05:00Z")
        )));

        assertThat(value).contains("\"startedAt\":\"2026-05-12T12:00:00Z\"");
        assertThat(value).contains("\"rawOutput\":\"议程完成\"");
        assertThat(value).doesNotContain("[]");
    }
}
