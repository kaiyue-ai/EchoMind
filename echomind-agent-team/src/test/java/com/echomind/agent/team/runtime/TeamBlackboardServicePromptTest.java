package com.echomind.agent.team.runtime;

import com.echomind.agent.team.model.PlannedStep;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.team.model.TeamMember;
import com.echomind.agent.team.state.TeamRiskLevel;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.state.TeamStepQualityStatus;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamStepEntity;
import com.echomind.agent.orchestration.AgentOrchestrator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamBlackboardServicePromptTest {

    @Test
    void plannerPromptIncludesReviewerRevisionInstructionsOnRetry() {
        TeamRunEntity run = new TeamRunEntity();
        run.setTask("策划活动");
        run.setClarificationAnswer("预算300元/人");
        TeamSnapshot team = new TeamSnapshot(
            "team-1",
            "default",
            "活动团队",
            List.of(new TeamMember("executor", "Executor", TeamRole.EXECUTOR, List.of("weather"), 20, null)),
            null,
            null
        );

        String prompt = TeamPromptFactory.planner(
            run,
            team,
            List.of(new PlannedStep("旧天气查询", "只查天气", List.of("weather"), "给出天气")),
            "补充天气风险和备用方案",
            List.of(),
            new TeamJsonSupport()
        );

        assertThat(prompt).contains("Previous plan");
        assertThat(prompt).contains("旧天气查询");
        assertThat(prompt).contains("补充天气风险和备用方案");
        assertThat(prompt).contains("预算300元/人");
        assertThat(prompt).contains("Do not create a final integration");
        assertThat(prompt).contains("The Reviewer is responsible for the final report");
    }

    @Test
    void plannerPromptIncludesPreviousExecutorResultsOnResultReplan() {
        TeamRunEntity run = new TeamRunEntity();
        run.setTask("策划活动");
        TeamSnapshot team = new TeamSnapshot(
            "team-1",
            "default",
            "活动团队",
            List.of(new TeamMember("executor", "Executor", TeamRole.EXECUTOR, List.of("weather"), 20, null)),
            null,
            null
        );

        String prompt = TeamPromptFactory.planner(
            run,
            team,
            List.of(new PlannedStep("旧天气查询", "只查天气", List.of("weather"), "给出天气")),
            "补充预算和人员协调任务",
            List.of(new TeamStepSnapshot(
                "step-1",
                1,
                "weather",
                "旧天气查询",
                "只查天气",
                List.of("weather"),
                List.of(),
                TeamRiskLevel.LOW,
                "",
                "给出天气",
                "executor",
                TeamStepStatus.COMPLETED,
                "旧执行结果：只有天气，没有预算。",
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
            )),
            new TeamJsonSupport()
        );

        assertThat(prompt).contains("Previous executor results");
        assertThat(prompt).contains("旧执行结果：只有天气，没有预算。");
        assertThat(prompt).contains("补充预算和人员协调任务");
    }

    @Test
    void executorPromptConstrainsSearchToolLoops() {
        TeamRunEntity run = new TeamRunEntity();
        run.setTask("策划公司团建活动");
        TeamStepEntity step = new TeamStepEntity();
        step.setStepId("step-1");
        step.setTitle("团建场地调研与筛选");
        step.setDescription("调研适合 30 人团建的场地");
        step.setAcceptanceCriteria("给出 3 个候选场地和取舍理由");

        String prompt = TeamPromptFactory.executor(run, step, "");

        assertThat(prompt).contains("When using search tools, call them only for distinct missing facts");
        assertThat(prompt).contains("Do not keep searching for the same fact");
        assertThat(prompt).contains("state the limitation and still produce the best step deliverable");
    }

    @Test
    void mergerPromptIncludesGlobalClarificationAnswer() {
        TeamRunEntity run = new TeamRunEntity();
        run.setTask("策划公司团建活动");
        run.setClarificationAnswer("最终方案请优先控制预算");

        String prompt = TeamPromptFactory.merger(run, List.of(), new TeamJsonSupport());

        assertThat(prompt).contains("User clarification, if any");
        assertThat(prompt).contains("最终方案请优先控制预算");
    }

    @Test
    void executorToolBudgetFallbackPromptForbidsToolsAndForcesSummary() {
        TeamRunEntity run = new TeamRunEntity();
        run.setTask("策划公司团建活动");
        TeamStepEntity step = new TeamStepEntity();
        step.setStepId("step-1");
        step.setTitle("团建场地调研与筛选");
        step.setDescription("调研适合 30 人团建的场地");
        step.setAcceptanceCriteria("给出 3 个候选场地和取舍理由");

        String prompt = TeamPromptFactory.executorToolBudgetFallback(
            run,
            step,
            "依赖输出",
            "工具调用次数超过上限(50)"
        );

        assertThat(prompt).contains("本轮禁止再调用任何工具");
        assertThat(prompt).contains("直接生成该 Step 的降级总结");
        assertThat(prompt).contains("工具调用次数超过上限(50)");
        assertThat(prompt).contains("依赖输出");
    }

    @Test
    void reviewerDecisionRepairsInvalidJsonOnce() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        TeamBlackboardService service = new TeamBlackboardService(
            null, null, null, null, null, null, orchestrator
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
        when(orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"), eq("原始审查提示"), eq(false)))
            .thenReturn(broken);
        when(orchestrator.executeInternal(eq("reviewer"), startsWith("team-run-run-1-reviewer-repair-"), contains("Invalid response excerpt"), eq(false)))
            .thenReturn(repaired);

        var decision = service.executeReviewerDecision(reviewer, run, "原始审查提示", true);

        assertThat(decision.reason()).isEqualTo("格式已修复");
        assertThat(decision.finalReport()).isEqualTo("最终报告");
        verify(orchestrator).executeInternal("reviewer", "team-run-run-1-reviewer", "原始审查提示", false);
    }

    @Test
    void teamCallPassesUsageReservationsIntoInternalPipelineContext() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        TeamBlackboardService service = new TeamBlackboardService(
            null, null, null, null, null, null, orchestrator
        );
        TeamUsageRecorder recorder = mock(TeamUsageRecorder.class);
        TeamUsageReservation reservation = new TeamUsageReservation(
            List.of("user-reservation"),
            List.of("provider-reservation")
        );
        service.setUsageRecorder(recorder);
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId("run-1");
        run.setUserId("user-a");
        TeamMember reviewer = new TeamMember("reviewer", "Reviewer", TeamRole.REVIEWER, List.of("review"), 30, null);
        PipelineContext ctx = new PipelineContext();
        ctx.setFinalResponse("""
            {"action":"CONTINUE","reason":"通过","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":"最终报告"}
            """);
        when(recorder.reserveUsage("user-a", "reviewer", "team-run-run-1-reviewer", "原始审查提示"))
            .thenReturn(reservation);
        when(orchestrator.executeInternal(eq("user-a"), eq("reviewer"), eq("team-run-run-1-reviewer"),
            eq("原始审查提示"), eq(false), argThat(TeamBlackboardServicePromptTest::containsUsageReservations)))
            .thenReturn(ctx);

        var decision = service.executeReviewerDecision(reviewer, run, "原始审查提示", true);

        assertThat(decision.finalReport()).isEqualTo("最终报告");
        assertThat(ctx.getAttributes())
            .containsEntry(PipelineContext.ATTR_USER_TOKEN_RESERVATION_IDS, List.of("user-reservation"))
            .containsEntry(PipelineContext.ATTR_PROVIDER_TOKEN_RESERVATION_IDS, List.of("provider-reservation"));
        verify(recorder).record(eq("echomind.team.reviewer"), eq("user-a"), eq("reviewer"),
            eq("team-run-run-1-reviewer"), eq(ctx), org.mockito.ArgumentMatchers.anyLong(), eq(false), eq(null));
    }

    private static boolean containsUsageReservations(Map<String, Object> attributes) {
        return attributes != null
            && List.of("user-reservation").equals(attributes.get(PipelineContext.ATTR_USER_TOKEN_RESERVATION_IDS))
            && List.of("provider-reservation").equals(attributes.get(PipelineContext.ATTR_PROVIDER_TOKEN_RESERVATION_IDS));
    }
}
