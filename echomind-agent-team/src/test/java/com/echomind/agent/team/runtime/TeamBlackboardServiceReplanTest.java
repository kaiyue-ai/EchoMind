package com.echomind.agent.team.runtime;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.team.state.TeamEventType;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamStepQualityStatus;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.store.TeamEntity;
import com.echomind.agent.team.store.TeamEventEntity;
import com.echomind.agent.team.store.TeamEventMapper;
import com.echomind.agent.team.store.TeamMemberEntity;
import com.echomind.agent.team.store.TeamMemberMapper;
import com.echomind.agent.team.store.TeamMapper;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamRunMapper;
import com.echomind.agent.team.store.TeamStepEntity;
import com.echomind.agent.team.store.TeamStepMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamBlackboardServiceReplanTest {

    @Test
    void simpleTaskUsesDraftFastPathAndGlobalReview() {
        RuntimeHarness harness = new RuntimeHarness();
        TeamRunEntity run = harness.run();
        harness.wire(run);

        when(harness.orchestrator.executeInternal(eq("planner"), eq("team-run-run-1-planner"), anyString(), eq(false)))
            .thenReturn(context("""
                {"taskLevel":"SIMPLE","steps":[{"clientStepId":"draft","title":"资料整理","description":"整理用户给出的材料","requiredCapabilities":["general"],"acceptanceCriteria":"输出清晰初稿"}]}
                """));
        when(harness.orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"), anyString(), eq(false)))
            .thenReturn(
                context("""
                    {"action":"CONTINUE","reason":"简易任务可直接执行","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":""}
                    """),
                context("""
                    {"action":"PASS","reason":"Step 输出可用","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":""}
                    """),
                context("""
                    {"action":"SUCCESS","reason":"简易终审通过","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":"简易最终报告"}
                    """)
            );
        when(harness.orchestrator.executeInternal(eq("executor"), startsWith("team-run-run-1-step-"), anyString()))
            .thenReturn(context("简易初稿"));

        harness.service.executeRun("run-1");

        assertThat(run.getStatus()).isEqualTo(TeamRunStatus.COMPLETED);
        assertThat(run.getFinalOutput()).isEqualTo("简易最终报告");
        assertThat(harness.persistedSteps)
            .singleElement()
            .satisfies(step -> {
                assertThat(step.getClientStepId()).isEqualTo("simple-draft");
                assertThat(step.getStatus()).isEqualTo(TeamStepStatus.COMPLETED);
            });
        assertThat(harness.events)
            .extracting(TeamEventEntity::getType)
            .contains(TeamEventType.TEAM_CONTROL_STARTED);
    }

    @Test
    void simpleFastPathReturnsExecutorOutputWithoutReviewOrMerge() {
        RuntimeHarness harness = new RuntimeHarness();
        TeamRunEntity run = harness.run();
        run.setSimpleFastPathEnabled(true);
        harness.wire(run);

        when(harness.orchestrator.executeInternal(eq("planner"), eq("team-run-run-1-planner"), anyString(), eq(false)))
            .thenReturn(context("""
                {"taskLevel":"SIMPLE","steps":[{"clientStepId":"draft","title":"资料整理","description":"整理用户给出的材料","requiredCapabilities":["general"],"acceptanceCriteria":"输出清晰初稿"}]}
                """));
        when(harness.orchestrator.executeInternal(eq("executor"), startsWith("team-run-run-1-step-"), anyString()))
            .thenReturn(context("简易初稿"));

        harness.service.executeRun("run-1");

        assertThat(run.getStatus()).isEqualTo(TeamRunStatus.COMPLETED);
        assertThat(run.getFinalOutput()).isEqualTo("简易初稿");
        verify(harness.orchestrator, never()).executeInternal(eq("reviewer"), anyString(), anyString(), eq(false));
        verify(harness.orchestrator, never()).executeInternal(eq("planner"), contains("agent-selector"), anyString(), eq(false));
        verify(harness.orchestrator, never()).executeInternal(eq("reviewer"), contains("conflict-detector"), anyString(), eq(false));
    }

    @Test
    void disabledPlanReviewSkipsReviewerButStillExecutesGlobalReview() {
        RuntimeHarness harness = new RuntimeHarness();
        TeamRunEntity run = harness.run();
        run.setPlanReviewEnabled(false);
        run.setSubReviewEnabled(false);
        harness.wire(run);

        when(harness.orchestrator.executeInternal(eq("planner"), eq("team-run-run-1-planner"), anyString(), eq(false)))
            .thenReturn(context("""
                {"taskLevel":"COMPLEX","steps":[{"clientStepId":"draft","title":"资料整理","description":"整理用户给出的材料","requiredCapabilities":["general"],"acceptanceCriteria":"输出清晰初稿"}]}
                """));
        when(harness.orchestrator.executeInternal(eq("executor"), startsWith("team-run-run-1-step-"), anyString()))
            .thenReturn(context("执行输出"));
        when(harness.orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"), contains("You are the GlobalReviewer"), eq(false)))
            .thenReturn(context("""
                {"action":"SUCCESS","reason":"终审通过","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":"最终报告"}
                """));

        harness.service.executeRun("run-1");

        assertThat(run.getStatus()).isEqualTo(TeamRunStatus.COMPLETED);
        assertThat(run.getPlanReviewJson()).contains("用户跳过 PlanReview");
        verify(harness.orchestrator, never()).executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"),
            contains("Review whether the Planner"), eq(false));
    }

    @Test
    void disabledGlobalReviewUsesMergeOutputAsFinalOutput() {
        RuntimeHarness harness = new RuntimeHarness();
        TeamRunEntity run = harness.run();
        run.setGlobalReviewEnabled(false);
        run.setSubReviewEnabled(false);
        harness.wire(run);

        when(harness.orchestrator.executeInternal(eq("planner"), eq("team-run-run-1-planner"), anyString(), eq(false)))
            .thenReturn(context("""
                {"taskLevel":"COMPLEX","steps":[{"clientStepId":"draft","title":"资料整理","description":"整理用户给出的材料","requiredCapabilities":["general"],"acceptanceCriteria":"输出清晰初稿"}]}
                """));
        when(harness.orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"), contains("Review whether the Planner"), eq(false)))
            .thenReturn(context("""
                {"action":"CONTINUE","reason":"计划通过","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":""}
                """));
        when(harness.orchestrator.executeInternal(eq("executor"), startsWith("team-run-run-1-step-"), anyString()))
            .thenReturn(context("执行输出"));
        when(harness.orchestrator.executeInternal(eq("reviewer"), contains("conflict-detector"), anyString(), eq(false)))
            .thenReturn(context("""
                {"hasConflict":false,"conflictFields":[],"affectedStepIds":[],"reason":"无冲突","normalizationAdvice":""}
                """));

        harness.service.executeRun("run-1");

        assertThat(run.getStatus()).isEqualTo(TeamRunStatus.COMPLETED);
        assertThat(run.getGlobalReviewJson()).contains("用户跳过 GlobalReview");
        assertThat(run.getFinalOutput()).contains("执行输出");
        verify(harness.orchestrator, never()).executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"),
            contains("You are the GlobalReviewer"), eq(false));
    }

    @Test
    void disabledStepReviewMarksHighRiskStepAsReviewSkipped() {
        RuntimeHarness harness = new RuntimeHarness();
        TeamRunEntity run = harness.run();
        run.setSubReviewEnabled(false);
        harness.wire(run);

        when(harness.orchestrator.executeInternal(eq("planner"), eq("team-run-run-1-planner"), anyString(), eq(false)))
            .thenReturn(context("""
                {"taskLevel":"COMPLEX","steps":[{"clientStepId":"risk","title":"风险任务","description":"处理高风险材料","requiredCapabilities":["general"],"acceptanceCriteria":"输出可交付结果","riskLevel":"HIGH","riskReason":"需要人工质量闸门"}]}
                """));
        when(harness.orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"), contains("Review whether the Planner"), eq(false)))
            .thenReturn(context("""
                {"action":"CONTINUE","reason":"计划通过","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":""}
                """));
        when(harness.orchestrator.executeInternal(eq("executor"), startsWith("team-run-run-1-step-"), anyString()))
            .thenReturn(context("高风险输出"));
        when(harness.orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"), contains("You are the GlobalReviewer"), eq(false)))
            .thenReturn(context("""
                {"action":"SUCCESS","reason":"终审通过","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":"最终报告"}
                """));

        harness.service.executeRun("run-1");

        assertThat(run.getStatus()).isEqualTo(TeamRunStatus.COMPLETED);
        assertThat(harness.persistedSteps)
            .singleElement()
            .satisfies(step -> {
                assertThat(step.getQualityStatus()).isEqualTo(TeamStepQualityStatus.REVIEW_SKIPPED);
                assertThat(step.getSubReviewJson()).contains("用户跳过每步 Review");
            });
        verify(harness.orchestrator, never()).executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"),
            contains("StepReviewer"), eq(false));
    }

    @Test
    void globalReviewRemergeRerunsOnlyMergeAgent() {
        RuntimeHarness harness = new RuntimeHarness();
        TeamRunEntity run = harness.run();
        harness.wire(run);

        when(harness.orchestrator.executeInternal(eq("planner"), eq("team-run-run-1-planner"), anyString(), eq(false)))
            .thenReturn(context("""
                {"taskLevel":"COMPLEX","steps":[{"clientStepId":"draft","title":"资料整理","description":"整理用户给出的材料","requiredCapabilities":["general"],"acceptanceCriteria":"输出清晰初稿"}]}
                """));
        when(harness.orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"),
            contains("Review whether the Planner"), eq(false)))
            .thenReturn(context("""
                {"action":"CONTINUE","reason":"计划通过","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":""}
                """));
        when(harness.orchestrator.executeInternal(eq("executor"), startsWith("team-run-run-1-step-"), anyString()))
            .thenReturn(context("执行输出"));
        when(harness.orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"),
            contains("StepReviewer"), eq(false)))
            .thenReturn(context("""
                {"action":"PASS","reason":"Step 输出可用","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":""}
                """));
        when(harness.orchestrator.executeInternal(eq("reviewer"), startsWith("team-run-run-1-merger-"),
            anyString(), eq(false)))
            .thenReturn(context("初版聚合稿"), context("按终审意见重合并后的稿件"));
        when(harness.orchestrator.executeInternal(eq("reviewer"), contains("conflict-detector"), anyString(), eq(false)))
            .thenReturn(context("""
                {"hasConflict":false,"conflictFields":[],"affectedStepIds":[],"reason":"无冲突","normalizationAdvice":""}
                """));
        when(harness.orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"),
            contains("You are the GlobalReviewer"), eq(false)))
            .thenReturn(
                context("""
                    {"action":"REMERGE","reason":"最终稿结构不清晰","questions":[],"retryStepIds":[],"affectedStepIds":[],"revisionInstructions":"按用户目标重新组织章节","finalReport":""}
                    """),
                context("""
                    {"action":"SUCCESS","reason":"终审通过","questions":[],"retryStepIds":[],"affectedStepIds":[],"revisionInstructions":"","finalReport":"最终报告"}
                    """)
            );

        harness.service.executeRun("run-1");

        assertThat(run.getStatus()).isEqualTo(TeamRunStatus.COMPLETED);
        assertThat(run.getResultReplanCount()).isZero();
        assertThat(run.getFinalOutput()).isEqualTo("最终报告");
        assertThat(run.getMergeOutput()).isEqualTo("按终审意见重合并后的稿件");
        assertThat(harness.persistedSteps)
            .singleElement()
            .satisfies(step -> assertThat(step.getStatus()).isEqualTo(TeamStepStatus.COMPLETED));
        assertThat(harness.events)
            .extracting(TeamEventEntity::getType)
            .contains(TeamEventType.RUN_COMPLETED)
            .doesNotContain(TeamEventType.REPLAN_REQUESTED);
    }

    @Test
    void globalReviewRejectsReplanActionWithoutChangingDag() {
        RuntimeHarness harness = new RuntimeHarness();
        TeamRunEntity run = harness.run();
        harness.persistedSteps.add(harness.step("step-old", 1, "旧天气查询", TeamStepStatus.COMPLETED, "只有天气"));
        harness.wire(run);

        when(harness.orchestrator.executeInternal(eq("reviewer"), startsWith("team-run-run-1-merger-"),
            anyString(), eq(false)))
            .thenReturn(context("已有 Step 的聚合稿"));
        when(harness.orchestrator.executeInternal(eq("reviewer"), contains("conflict-detector"), anyString(), eq(false)))
            .thenReturn(context("""
                {"hasConflict":false,"conflictFields":[],"affectedStepIds":[],"reason":"无冲突","normalizationAdvice":""}
                """));
        when(harness.orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"),
            contains("You are the GlobalReviewer"), eq(false)))
            .thenReturn(context("""
                {"action":"REPLAN","reason":"仍然缺少预算任务","questions":[],"retryStepIds":[],"revisionInstructions":"重新规划预算","finalReport":""}
                """));
        when(harness.orchestrator.executeInternal(eq("reviewer"), startsWith("team-run-run-1-reviewer-repair-"),
            anyString(), eq(false)))
            .thenReturn(context("""
                {"action":"FAILED","reason":"GlobalReviewer 不允许重规划 DAG","questions":[],"retryStepIds":[],"affectedStepIds":[],"revisionInstructions":"","finalReport":""}
                """));

        harness.service.executeRun("run-1");

        assertThat(run.getStatus()).isEqualTo(TeamRunStatus.FAILED);
        assertThat(run.getFinalOutput()).contains("GlobalReviewer 不允许重规划 DAG");
        assertThat(harness.persistedSteps)
            .singleElement()
            .satisfies(step -> assertThat(step.getStatus()).isEqualTo(TeamStepStatus.COMPLETED));
        verify(harness.orchestrator, never()).executeInternal(eq("planner"), anyString(), contains("Previous executor results"), eq(false));
    }

    @Test
    void executorToolBudgetLimitFallsBackToToollessSummary() {
        RuntimeHarness harness = new RuntimeHarness();
        TeamRunEntity run = harness.run();
        harness.wire(run);

        when(harness.orchestrator.executeInternal(eq("planner"), eq("team-run-run-1-planner"), anyString(), eq(false)))
            .thenReturn(context("""
                {"taskLevel":"SIMPLE","steps":[{"clientStepId":"draft","title":"资料整理","description":"整理用户给出的材料","requiredCapabilities":["general"],"acceptanceCriteria":"输出清晰初稿"}]}
                """));
        when(harness.orchestrator.executeInternal(eq("reviewer"), eq("team-run-run-1-reviewer"), anyString(), eq(false)))
            .thenReturn(
                context("""
                    {"action":"CONTINUE","reason":"简易任务可直接执行","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":""}
                    """),
                context("""
                    {"action":"SUCCESS","reason":"简易终审通过","questions":[],"retryStepIds":[],"revisionInstructions":"","finalReport":"简易最终报告"}
                    """)
            );
        when(harness.orchestrator.executeInternal(eq("executor"), startsWith("team-run-run-1-step-"), anyString()))
            .thenReturn(context("[Error] 工具调用次数超过上限(50)，已中止本轮模型调用，防止工具循环。最后一次工具: open_web_search"));
        when(harness.orchestrator.executeInternal(eq("executor"),
            startsWith("team-run-run-1-step-"), contains("本轮禁止再调用任何工具"), eq(false)))
            .thenReturn(context("基于已有信息的降级总结"));

        harness.service.executeRun("run-1");

        assertThat(run.getStatus()).isEqualTo(TeamRunStatus.COMPLETED);
        assertThat(harness.persistedSteps)
            .singleElement()
            .satisfies(step -> {
                assertThat(step.getStatus()).isEqualTo(TeamStepStatus.COMPLETED);
                assertThat(step.getQualityStatus()).isEqualTo(TeamStepQualityStatus.FLAWED_ACCEPTED);
                assertThat(step.getRawOutput()).contains("工具调用达到上限，已降级总结");
                assertThat(step.getRawOutput()).contains("基于已有信息的降级总结");
            });
        verify(harness.orchestrator).executeInternal(eq("executor"),
            startsWith("team-run-run-1-step-"), contains("本轮禁止再调用任何工具"), eq(false));
    }

    @Test
    void lateExecutorCompletionDoesNotOverwriteTimedOutStep() {
        RuntimeHarness harness = new RuntimeHarness();
        TeamRunEntity run = harness.run();
        run.setSubReviewEnabled(false);
        run.setGlobalReviewEnabled(false);
        TeamStepEntity step = harness.step("step-1", 1, "慢步骤", TeamStepStatus.READY, null);
        harness.persistedSteps.add(step);
        harness.wire(run);

        when(harness.orchestrator.executeInternal(eq("executor"), startsWith("team-run-run-1-step-"), anyString()))
            .thenAnswer(invocation -> {
                harness.service.markStepTimedOut("run-1", "step-1");
                return context("迟到输出");
            });

        harness.service.executeRun("run-1");

        assertThat(run.getStatus()).isEqualTo(TeamRunStatus.FAILED);
        assertThat(harness.persistedSteps)
            .singleElement()
            .satisfies(persisted -> {
                assertThat(persisted.getStatus()).isEqualTo(TeamStepStatus.FAILED);
                assertThat(persisted.getRawOutput()).contains("Step execution timed out");
                assertThat(persisted.getRawOutput()).doesNotContain("迟到输出");
            });
        assertThat(harness.events)
            .extracting(TeamEventEntity::getType)
            .contains(TeamEventType.STEP_TIMEOUT, TeamEventType.RUN_FAILED)
            .doesNotContain(TeamEventType.STEP_COMPLETED, TeamEventType.RUN_COMPLETED);
    }

    @Test
    void lateStepFailureDoesNotOverwriteCompletedStep() {
        RuntimeHarness harness = new RuntimeHarness();
        TeamRunEntity run = harness.run();
        TeamStepEntity step = harness.step("step-1", 1, "已完成步骤", TeamStepStatus.COMPLETED, "完成输出");
        step.setQualityStatus(TeamStepQualityStatus.PASSED);
        harness.persistedSteps.add(step);
        harness.wire(run);

        harness.service.markStepFailed("run-1", "step-1", "迟到失败");

        assertThat(harness.persistedSteps)
            .singleElement()
            .satisfies(persisted -> {
                assertThat(persisted.getStatus()).isEqualTo(TeamStepStatus.COMPLETED);
                assertThat(persisted.getRawOutput()).isEqualTo("完成输出");
                assertThat(persisted.getQualityStatus()).isEqualTo(TeamStepQualityStatus.PASSED);
            });
        assertThat(harness.events)
            .extracting(TeamEventEntity::getType)
            .doesNotContain(TeamEventType.STEP_FAILED);
    }

    @Test
    void lateStepTimeoutDoesNotOverwriteCompletedStep() {
        RuntimeHarness harness = new RuntimeHarness();
        TeamRunEntity run = harness.run();
        TeamStepEntity step = harness.step("step-1", 1, "已完成步骤", TeamStepStatus.COMPLETED, "完成输出");
        step.setQualityStatus(TeamStepQualityStatus.PASSED);
        harness.persistedSteps.add(step);
        harness.wire(run);

        harness.service.markStepTimedOut("run-1", "step-1");

        assertThat(harness.persistedSteps)
            .singleElement()
            .satisfies(persisted -> {
                assertThat(persisted.getStatus()).isEqualTo(TeamStepStatus.COMPLETED);
                assertThat(persisted.getRawOutput()).isEqualTo("完成输出");
                assertThat(persisted.getQualityStatus()).isEqualTo(TeamStepQualityStatus.PASSED);
            });
        assertThat(harness.events)
            .extracting(TeamEventEntity::getType)
            .doesNotContain(TeamEventType.STEP_TIMEOUT, TeamEventType.STEP_FAILED);
    }

    private static PipelineContext context(String finalResponse) {
        PipelineContext context = new PipelineContext();
        context.setFinalResponse(finalResponse);
        return context;
    }

    private static final class RuntimeHarness {
        private final TeamMapper teamMapper = mock(TeamMapper.class);
        private final TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
        private final TeamRunMapper runMapper = mock(TeamRunMapper.class);
        private final TeamStepMapper stepMapper = mock(TeamStepMapper.class);
        private final TeamEventMapper eventMapper = mock(TeamEventMapper.class);
        private final AgentFactory agentFactory = mock(AgentFactory.class);
        private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        private final List<TeamStepEntity> persistedSteps = new ArrayList<>();
        private final List<TeamEventEntity> events = new ArrayList<>();
        private final TeamBlackboardService service = new TeamBlackboardService(
            teamMapper,
            memberMapper,
            runMapper,
            stepMapper,
            eventMapper,
            agentFactory,
            orchestrator,
            Runnable::run
        );

        private void wire(TeamRunEntity run) {
            TeamEntity team = new TeamEntity();
            team.setTeamId("team-1");
            team.setOwnerUserId("default");
            team.setName("测试团队");
            when(teamMapper.selectOptionalById("team-1")).thenReturn(Optional.of(team));
            when(teamMapper.selectOptionalByTeamIdAndOwnerUserId("team-1", "default")).thenReturn(Optional.of(team));
            when(runMapper.selectOptionalById("run-1")).thenReturn(Optional.of(run));
            when(runMapper.existsById("run-1")).thenReturn(true);
            when(runMapper.tryAcquireExecuteLock("run-1")).thenAnswer(invocation -> {
                run.setStatus(TeamRunStatus.EXECUTING);
                return true;
            });
            when(runMapper.upsertById(any(TeamRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(memberMapper.selectByTeamIdOrderBySortOrderAscIdAsc("team-1")).thenReturn(List.of(
                member("planner", TeamRole.PLANNER, 10),
                member("executor", TeamRole.EXECUTOR, 20),
                member("reviewer", TeamRole.REVIEWER, 30)
            ));
            Agent planner = agent("planner");
            Agent executor = agent("executor");
            Agent reviewer = agent("reviewer");
            when(agentFactory.get("planner")).thenReturn(planner);
            when(agentFactory.get("executor")).thenReturn(executor);
            when(agentFactory.get("reviewer")).thenReturn(reviewer);
            when(stepMapper.selectByRunIdOrderByStepIndexAsc("run-1")).thenAnswer(invocation -> persistedSteps.stream()
                .sorted(Comparator.comparingInt(TeamStepEntity::getStepIndex))
                .toList());
            when(stepMapper.selectOptionalById(anyString())).thenAnswer(invocation -> {
                String stepId = invocation.getArgument(0);
                return persistedSteps.stream()
                    .filter(step -> step.getStepId().equals(stepId))
                    .findFirst();
            });
            when(stepMapper.upsertById(any(TeamStepEntity.class))).thenAnswer(invocation -> {
                TeamStepEntity step = invocation.getArgument(0);
                persistedSteps.removeIf(existing -> existing.getStepId().equals(step.getStepId()));
                persistedSteps.add(step);
                return step;
            });
            doAnswer(invocation -> {
                persistedSteps.clear();
                return null;
            }).when(stepMapper).deleteByRunId("run-1");
            when(eventMapper.selectByRunIdOrderByCreatedAtAscIdAsc("run-1")).thenAnswer(invocation -> List.copyOf(events));
            when(eventMapper.upsertById(any(TeamEventEntity.class))).thenAnswer(invocation -> {
                TeamEventEntity event = invocation.getArgument(0);
                events.add(event);
                return event;
            });
        }

        private TeamRunEntity run() {
            TeamRunEntity run = new TeamRunEntity();
            run.setRunId("run-1");
            run.setTeamId("team-1");
            run.setTask("策划活动");
            run.setStatus(TeamRunStatus.PENDING);
            return run;
        }

        private TeamStepEntity step(String stepId, int stepIndex, String title, TeamStepStatus status, String output) {
            TeamStepEntity step = new TeamStepEntity();
            step.setStepId(stepId);
            step.setRunId("run-1");
            step.setStepIndex(stepIndex);
            step.setTitle(title);
            step.setDescription(title);
            step.setRequiredCapabilitiesJson("[\"general\"]");
            step.setAcceptanceCriteria("输出可交付结果");
            step.setStatus(status);
            step.setRawOutput(output);
            return step;
        }

        private TeamMemberEntity member(String agentId, TeamRole role, int sortOrder) {
            TeamMemberEntity member = new TeamMemberEntity();
            member.setId((long) sortOrder);
            member.setTeamId("team-1");
            member.setAgentId(agentId);
            member.setRole(role);
            member.setCapabilityTagsJson("[\"general\"]");
            member.setSortOrder(sortOrder);
            return member;
        }

        private Agent agent(String id) {
            Agent agent = mock(Agent.class);
            when(agent.getAgentId()).thenReturn(id);
            when(agent.getName()).thenReturn(id);
            return agent;
        }
    }
}
