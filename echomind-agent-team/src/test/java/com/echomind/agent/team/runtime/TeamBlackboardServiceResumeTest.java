package com.echomind.agent.team.runtime;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.team.state.TeamClarificationStage;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.store.TeamEntity;
import com.echomind.agent.team.store.TeamEventMapper;
import com.echomind.agent.team.store.TeamMemberEntity;
import com.echomind.agent.team.store.TeamMemberMapper;
import com.echomind.agent.team.store.TeamMapper;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamRunMapper;
import com.echomind.agent.team.store.TeamStepEntity;
import com.echomind.agent.team.store.TeamStepMapper;
import org.junit.jupiter.api.Test;


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamBlackboardServiceResumeTest {

    private final TeamMapper teamMapper = mock(TeamMapper.class);
    private final TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
    private final TeamRunMapper runMapper = mock(TeamRunMapper.class);
    private final TeamStepMapper stepMapper = mock(TeamStepMapper.class);
    private final TeamEventMapper eventMapper = mock(TeamEventMapper.class);
    private final AgentFactory agentFactory = mock(AgentFactory.class);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final TeamJsonSupport json = new TeamJsonSupport();

    @Test
    void resumePlanClarificationClearsExistingStepsForReplanning() {
        TeamRunEntity run = run(TeamClarificationStage.PLAN_REVIEW);
        TeamStepEntity oldStep = step("step-1", TeamStepStatus.COMPLETED, "old raw");
        wireSnapshotData(run, List.of(oldStep));

        TeamRunSnapshot snapshot = service().resumeRun("team-1", "default", "run-1", "预算300元/人", Map.of());

        assertThat(snapshot.status()).isEqualTo(TeamRunStatus.PENDING);
        assertThat(snapshot.clarificationStage()).isNull();
        assertThat(snapshot.steps()).isEmpty();
        assertThat(run.getClarificationAnswer()).isEqualTo("预算300元/人");
        assertThat(run.getPlanReviewJson()).isNull();
        assertThat(run.getResultReviewJson()).isNull();
        assertThat(run.getFinalOutput()).isNull();
        assertThat(run.getMermaidDiagram()).isNull();
        assertThat(run.getStatus()).isEqualTo(TeamRunStatus.PENDING);
        verify(stepMapper).deleteByRunId("run-1");
    }

    @Test
    void resumeResultClarificationKeepsCompletedStepsForMergeReview() {
        TeamRunEntity run = run(TeamClarificationStage.RESULT_REVIEW);
        TeamStepEntity oldStep = step("step-1", TeamStepStatus.COMPLETED, "old raw");
        wireSnapshotData(run, List.of(oldStep));

        TeamRunSnapshot snapshot = service().resumeRun("team-1", "default", "run-1", "人数改为20人", Map.of());

        assertThat(snapshot.status()).isEqualTo(TeamRunStatus.PENDING);
        assertThat(snapshot.clarificationStage()).isNull();
        assertThat(snapshot.steps()).hasSize(1);
        TeamStepSnapshot completed = snapshot.steps().get(0);
        assertThat(completed.status()).isEqualTo(TeamStepStatus.COMPLETED);
        assertThat(completed.revisionInstructions()).isBlank();
        assertThat(json.stringList(oldStep.getPreviousOutputsJson())).isEmpty();
        assertThat(run.getResultReviewJson()).isNull();
        assertThat(run.getGlobalReviewJson()).isNull();
        assertThat(run.getMergeOutput()).isNull();
        assertThat(run.getConflictReportJson()).isNull();
        assertThat(run.getArbitrationJson()).isNull();
        assertThat(run.getFinalOutput()).isNull();
        assertThat(run.getMermaidDiagram()).isNull();
        verify(stepMapper, never()).deleteByRunId("run-1");
    }

    @Test
    void resumeResultClarificationRetriesOnlyNonTerminalSteps() {
        TeamRunEntity run = run(TeamClarificationStage.RESULT_REVIEW);
        TeamStepEntity completedStep = step("step-1", TeamStepStatus.COMPLETED, "completed raw");
        TeamStepEntity runningStep = step("step-2", TeamStepStatus.RUNNING, "running raw");
        wireSnapshotData(run, List.of(completedStep, runningStep));

        TeamRunSnapshot snapshot = service().resumeRun("team-1", "default", "run-1", "补充预算上限", Map.of());

        assertThat(snapshot.status()).isEqualTo(TeamRunStatus.PENDING);
        assertThat(snapshot.steps()).hasSize(2);
        assertThat(snapshot.steps())
            .filteredOn(step -> step.stepId().equals("step-1"))
            .singleElement()
            .satisfies(step -> assertThat(step.status()).isEqualTo(TeamStepStatus.COMPLETED));
        assertThat(snapshot.steps())
            .filteredOn(step -> step.stepId().equals("step-2"))
            .singleElement()
            .satisfies(step -> {
                assertThat(step.status()).isEqualTo(TeamStepStatus.RUNNING);
                assertThat(step.revisionInstructions()).isBlank();
            });
        assertThat(json.stringList(completedStep.getPreviousOutputsJson())).isEmpty();
        assertThat(json.stringList(runningStep.getPreviousOutputsJson())).isEmpty();
        assertThat(run.getMergeOutput()).isNull();
        assertThat(run.getGlobalReviewJson()).isNull();
    }

    @Test
    void resumeStepClarificationRetriesOnlyAskedStepAndWaitsForActiveSiblings() {
        TeamRunEntity run = run(TeamClarificationStage.RESULT_REVIEW);
        TeamStepEntity askedStep = step("step-1", TeamStepStatus.RUNNING, "asked raw");
        askedStep.setClientStepId("weather");
        askedStep.setReviewStatus("ASK_CLARIFICATION");
        askedStep.setSubReviewJson("""
            {"action":"ASK_CLARIFICATION","reason":"缺少日期","questions":["活动日期是哪天？"],"retryStepIds":[],"revisionInstructions":"","finalReport":""}
            """);
        TeamStepEntity otherRunningStep = step("step-2", TeamStepStatus.RUNNING, "other raw");
        wireSnapshotData(run, List.of(askedStep, otherRunningStep));

        TeamRunSnapshot snapshot = service().resumeRun("team-1", "default", "run-1", null,
            Map.of("step-1", "活动日期为6月1日"));

        assertThat(snapshot.status()).isEqualTo(TeamRunStatus.EXECUTING);
        assertThat(askedStep.getStatus()).isEqualTo(TeamStepStatus.RETRYING);
        assertThat(askedStep.getReviewStatus()).isEqualTo("RETRY_REQUESTED");
        assertThat(askedStep.getRevisionInstructions()).contains("活动日期为6月1日");
        assertThat(json.stringList(askedStep.getPreviousOutputsJson())).containsExactly("asked raw");
        assertThat(otherRunningStep.getStatus()).isEqualTo(TeamStepStatus.RUNNING);
        assertThat(otherRunningStep.getRevisionInstructions()).isBlank();
        assertThat(json.stringList(otherRunningStep.getPreviousOutputsJson())).isEmpty();
        assertThat(run.getClarificationAnswer()).isNull();
        assertThat(run.getMergeOutput()).isNull();
        assertThat(run.getGlobalReviewJson()).isNull();
        assertThat(scheduled).isEmpty();
    }

    @Test
    void resumeStepClarificationSchedulesWhenNoActiveSiblingsRemain() {
        TeamRunEntity run = run(TeamClarificationStage.RESULT_REVIEW);
        TeamStepEntity askedStep = step("step-1", TeamStepStatus.RUNNING, "asked raw");
        askedStep.setReviewStatus("ASK_CLARIFICATION");
        TeamStepEntity completedStep = step("step-2", TeamStepStatus.COMPLETED, "completed raw");
        wireSnapshotData(run, List.of(askedStep, completedStep));

        TeamRunSnapshot snapshot = service().resumeRun("team-1", "default", "run-1", null,
            Map.of("step-1", "澄清答案"));

        assertThat(snapshot.status()).isEqualTo(TeamRunStatus.PENDING);
        assertThat(askedStep.getStatus()).isEqualTo(TeamStepStatus.RETRYING);
        assertThat(completedStep.getStatus()).isEqualTo(TeamStepStatus.COMPLETED);
    }

    @Test
    void resumeStepClarificationRejectsRunLevelAnswer() {
        TeamRunEntity run = run(TeamClarificationStage.RESULT_REVIEW);
        TeamStepEntity askedStep = step("step-1", TeamStepStatus.RUNNING, "asked raw");
        askedStep.setReviewStatus("ASK_CLARIFICATION");
        wireSnapshotData(run, List.of(askedStep));

        assertThatThrownBy(() -> service().resumeRun("team-1", "default", "run-1", "错误字段答案", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stepClarificationAnswers is required");

        assertThat(askedStep.getStatus()).isEqualTo(TeamStepStatus.RUNNING);
        assertThat(scheduled).isEmpty();
    }

    @Test
    void resumeGlobalClarificationRejectsStepAnswers() {
        TeamRunEntity run = run(TeamClarificationStage.RESULT_REVIEW);
        TeamStepEntity completedStep = step("step-1", TeamStepStatus.COMPLETED, "completed raw");
        wireSnapshotData(run, List.of(completedStep));

        assertThatThrownBy(() -> service().resumeRun("team-1", "default", "run-1", null,
            Map.of("step-1", "错误字段答案")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("clarificationAnswer is required");

        assertThat(completedStep.getStatus()).isEqualTo(TeamStepStatus.COMPLETED);
        assertThat(scheduled).isEmpty();
    }

    @Test
    void resumeStepClarificationSupportsMultipleAnswersByStepId() {
        TeamRunEntity run = run(TeamClarificationStage.RESULT_REVIEW);
        TeamStepEntity firstStep = step("step-1", TeamStepStatus.RUNNING, "first raw");
        firstStep.setReviewStatus("ASK_CLARIFICATION");
        TeamStepEntity secondStep = step("step-2", TeamStepStatus.RUNNING, "second raw");
        secondStep.setReviewStatus("ASK_CLARIFICATION");
        wireSnapshotData(run, List.of(firstStep, secondStep));

        service().resumeRun("team-1", "default", "run-1", null,
            Map.of("step-1", "第一步答案", "step-2", "第二步答案"));

        assertThat(firstStep.getStatus()).isEqualTo(TeamStepStatus.RETRYING);
        assertThat(firstStep.getRevisionInstructions()).contains("第一步答案");
        assertThat(secondStep.getStatus()).isEqualTo(TeamStepStatus.RETRYING);
        assertThat(secondStep.getRevisionInstructions()).contains("第二步答案");
    }

    private TeamBlackboardService service() {
        return new TeamBlackboardService(
            teamMapper,
            memberMapper,
            runMapper,
            stepMapper,
            eventMapper,
            agentFactory,
            null
        );
    }

    private void wireSnapshotData(TeamRunEntity run, List<TeamStepEntity> steps) {
        List<TeamStepEntity> persistedSteps = new ArrayList<>(steps);
        TeamEntity team = new TeamEntity();
        team.setTeamId("team-1");
        team.setOwnerUserId("default");
        team.setName("测试团队");
        when(teamMapper.selectOptionalByTeamIdAndOwnerUserId("team-1", "default")).thenReturn(Optional.of(team));
        when(runMapper.selectOptionalById("run-1")).thenReturn(Optional.of(run));
        when(stepMapper.selectByRunIdOrderByStepIndexAsc("run-1")).thenAnswer(invocation -> List.copyOf(persistedSteps));
        when(eventMapper.selectByRunIdOrderByCreatedAtAscIdAsc("run-1")).thenReturn(List.of());
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
        when(runMapper.upsertById(any(TeamRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stepMapper.upsertById(any(TeamStepEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            persistedSteps.clear();
            return null;
        }).when(stepMapper).deleteByRunId("run-1");
    }

    private Agent agent(String id) {
        Agent agent = mock(Agent.class);
        when(agent.getAgentId()).thenReturn(id);
        when(agent.getName()).thenReturn(id);
        return agent;
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

    private TeamRunEntity run(TeamClarificationStage stage) {
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId("run-1");
        run.setTeamId("team-1");
        run.setTask("策划活动");
        run.setStatus(TeamRunStatus.NEEDS_CLARIFICATION);
        run.setClarificationStage(stage);
        run.setClarificationQuestion("请补充信息");
        run.setPlanReviewJson("{\"action\":\"ASK_CLARIFICATION\"}");
        run.setResultReviewJson("{\"action\":\"ASK_CLARIFICATION\"}");
        run.setGlobalReviewJson("{\"action\":\"ASK_CLARIFICATION\"}");
        run.setMergeOutput("old merge");
        run.setConflictReportJson("{\"hasConflict\":false}");
        run.setArbitrationJson("{\"arbitration\":\"old\"}");
        run.setFinalOutput("old final");
        run.setMermaidDiagram("old mermaid");
        return run;
    }

    private TeamStepEntity step(String stepId, TeamStepStatus status, String output) {
        TeamStepEntity step = new TeamStepEntity();
        step.setStepId(stepId);
        step.setRunId("run-1");
        step.setStepIndex(1);
        step.setTitle("天气查询");
        step.setDescription("查询天气");
        step.setRequiredCapabilitiesJson("[\"weather\"]");
        step.setStatus(status);
        step.setRawOutput(output);
        step.setStartedAt(Instant.now());
        step.setCompletedAt(Instant.now());
        return step;
    }

}
