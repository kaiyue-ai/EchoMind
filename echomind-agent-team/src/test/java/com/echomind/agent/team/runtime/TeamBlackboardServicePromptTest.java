package com.echomind.agent.team.runtime;

import com.echomind.agent.team.model.PlannedStep;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.team.model.TeamMember;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.orchestration.AgentOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamBlackboardServicePromptTest {

    @Test
    void plannerPromptIncludesReviewerRevisionInstructionsOnRetry() {
        TeamBlackboardService service = new TeamBlackboardService(
            null, null, null, null, null, null, null, null
        );
        TeamRunEntity run = new TeamRunEntity();
        run.setTask("策划活动");
        run.setClarificationAnswer("预算300元/人");
        TeamSnapshot team = new TeamSnapshot(
            "team-1",
            "活动团队",
            List.of(new TeamMember("executor", "Executor", TeamRole.EXECUTOR, List.of("weather"), 20, null)),
            null,
            null
        );

        String prompt = service.buildPlannerPrompt(
            run,
            team,
            List.of(new PlannedStep("旧天气查询", "只查天气", List.of("weather"), "给出天气")),
            "补充天气风险和备用方案"
        );

        assertThat(prompt).contains("Previous plan");
        assertThat(prompt).contains("旧天气查询");
        assertThat(prompt).contains("补充天气风险和备用方案");
        assertThat(prompt).contains("预算300元/人");
        assertThat(prompt).contains("Do not create a final integration");
        assertThat(prompt).contains("The Reviewer is responsible for the final report");
    }

    @Test
    void reviewerDecisionRepairsInvalidJsonOnce() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        TeamBlackboardService service = new TeamBlackboardService(
            null, null, null, null, null, null, orchestrator, null
        );
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId("run-1");
        TeamMember reviewer = new TeamMember("reviewer", "Reviewer", TeamRole.REVIEWER, List.of("review"), 30, null);
        PipelineContext broken = new PipelineContext();
        broken.setFinalResponse("{\"action\":\"CONTINUE\",\"reason\":\"ok\" \"finalReport\":\"坏 JSON\"}");
        PipelineContext repaired = new PipelineContext();
        repaired.setFinalResponse("""
            {"action":"CONTINUE","reason":"格式已修复","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":"最终报告"}
            """);
        when(orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"), eq("原始审查提示")))
            .thenReturn(broken);
        when(orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer-repair"), contains("Invalid response to repair")))
            .thenReturn(repaired);

        var decision = service.executeReviewerDecision(reviewer, run, "原始审查提示", true);

        assertThat(decision.reason()).isEqualTo("格式已修复");
        assertThat(decision.finalReport()).isEqualTo("最终报告");
    }
}
