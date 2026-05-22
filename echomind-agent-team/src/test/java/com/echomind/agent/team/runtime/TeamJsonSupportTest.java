package com.echomind.agent.team.runtime;

import com.echomind.agent.team.state.ReviewerAction;
import com.echomind.agent.team.state.TeamRiskLevel;
import com.echomind.agent.team.state.TeamStepQualityStatus;
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
    void parseDecisionAcceptsResultReplanAction() {
        var decision = json.parseDecision("""
            {"action":"REPLAN","reason":"缺少预算任务","revisionInstructions":"重新规划预算和人员协调 Step"}
            """);

        assertThat(decision.action()).isEqualTo(ReviewerAction.REPLAN);
        assertThat(decision.retryStepIds()).isEmpty();
        assertThat(decision.revisionInstructions()).contains("重新规划");
    }

    @Test
    void parsePlanAcceptsDagAndRiskMetadata() {
        var steps = json.parsePlan("""
            {
              "taskLevel": "COMPLEX",
              "steps": [
                {
                  "clientStepId": "weather",
                  "title": "天气风险评估",
                  "description": "查询天气并给出风险预案",
                  "dependsOn": [],
                  "riskLevel": "HIGH",
                  "riskReason": "下游预算和流程依赖天气判断",
                  "requiredCapabilities": ["weather"],
                  "acceptanceCriteria": "包含天气摘要和风险预案"
                },
                {
                  "clientStepId": "budget",
                  "title": "预算拆分",
                  "description": "基于天气方案估算预算",
                  "dependsOn": ["weather"],
                  "riskLevel": "LOW",
                  "requiredCapabilities": ["math"],
                  "acceptanceCriteria": "包含总预算和人均预算"
                }
              ]
            }
            """);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).clientStepId()).isEqualTo("weather");
        assertThat(steps.get(0).riskLevel()).isEqualTo(TeamRiskLevel.HIGH);
        assertThat(steps.get(1).dependsOn()).containsExactly("weather");
    }

    @Test
    void parseConflictReportAcceptsStructuredDetectorOutput() {
        TeamConflictReport report = json.parseConflictReport("""
            {
              "hasConflict": true,
              "conflictFields": ["预算总额", "出发时间"],
              "affectedStepIds": ["budget", "schedule"],
              "reason": "预算和时间口径不一致",
              "normalizationAdvice": "以预算 Step 和人员协调 Step 为准"
            }
            """);

        assertThat(report.hasConflict()).isTrue();
        assertThat(report.conflictFields()).containsExactly("预算总额", "出发时间");
        assertThat(report.affectedStepIds()).containsExactly("budget", "schedule");
        assertThat(report.normalizationAdvice()).contains("人员协调");
    }

    @Test
    void parseExecutorSelectionDecisionAcceptsMemberKey() {
        TeamExecutorSelectionDecision decision = json.parseExecutorSelectionDecision("""
            {"memberKey":"default#20","agentId":"default","reason":"天气 Step 更匹配 weather 执行者"}
            """);

        assertThat(decision.memberKey()).isEqualTo("default#20");
        assertThat(decision.agentId()).isEqualTo("default");
        assertThat(decision.reason()).contains("weather");
    }

    @Test
    void parseDecisionAcceptsPartialReplanReflections() {
        var decision = json.parseDecision("""
            {
              "action": "PARTIAL_REPLAN",
              "reason": "预算和总方案口径冲突",
              "affectedStepIds": ["budget"],
              "revisionInstructions": "统一总预算",
              "stepReflections": {
                "budget": {
                  "failedCriteria": ["预算口径一致"],
                  "reviewReason": "总价不一致",
                  "revisionInstructions": "按最新预算表重算",
                  "previousOutputSummary": "旧输出为12000元",
                  "avoidRepeating": ["不要混用旧预算"]
                }
              }
            }
            """);

        assertThat(decision.action()).isEqualTo(ReviewerAction.PARTIAL_REPLAN);
        assertThat(decision.affectedStepIds()).containsExactly("budget");
        assertThat(decision.stepReflections()).containsKey("budget");
        assertThat(decision.stepReflections().get("budget").revisionInstructions()).contains("重算");
    }

    @Test
    void serializesTeamSnapshotsWithJavaTimeFields() {
        String value = json.toJson(List.of(new TeamStepSnapshot(
            "step-1",
            1,
            "agenda",
            "准备议程",
            "整理技术分享会议程",
            List.of("general"),
            List.of(),
            TeamRiskLevel.LOW,
            "",
            "输出可执行议程",
            "default",
            TeamStepStatus.COMPLETED,
            "议程完成",
            "",
            "",
            TeamStepQualityStatus.PASSED,
            "",
            "",
            "",
            0,
            0,
            Instant.parse("2026-05-12T12:00:00Z"),
            Instant.parse("2026-05-12T12:05:00Z")
        )));

        assertThat(value).contains("\"startedAt\":\"2026-05-12T12:00:00Z\"");
        assertThat(value).contains("\"rawOutput\":\"议程完成\"");
        assertThat(value).contains("\"dependsOnStepIds\":[]");
    }
}
