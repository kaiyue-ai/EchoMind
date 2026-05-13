package com.echomind.agent.team.runtime;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.team.state.TeamClarificationStage;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.store.TeamEntity;
import com.echomind.agent.team.store.TeamEventRepository;
import com.echomind.agent.team.store.TeamMemberEntity;
import com.echomind.agent.team.store.TeamMemberRepository;
import com.echomind.agent.team.store.TeamRepository;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamRunRepository;
import com.echomind.agent.team.store.TeamStepEntity;
import com.echomind.agent.team.store.TeamStepRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamBlackboardServiceResumeTest {

    private final TeamRepository teamRepository = mock(TeamRepository.class);
    private final TeamMemberRepository memberRepository = mock(TeamMemberRepository.class);
    private final TeamRunRepository runRepository = mock(TeamRunRepository.class);
    private final TeamStepRepository stepRepository = mock(TeamStepRepository.class);
    private final TeamEventRepository eventRepository = mock(TeamEventRepository.class);
    private final AgentFactory agentFactory = mock(AgentFactory.class);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final TeamJsonSupport json = new TeamJsonSupport();

    @Test
    void resumePlanClarificationClearsExistingStepsForReplanning() {
        TeamRunEntity run = run(TeamClarificationStage.PLAN_REVIEW);
        TeamStepEntity oldStep = step("step-1", TeamStepStatus.COMPLETED, "old raw");
        wireSnapshotData(run, List.of(oldStep));

        TeamRunSnapshot snapshot = service().resumeRun("team-1", "run-1", "预算300元/人");

        assertThat(snapshot.status()).isEqualTo(TeamRunStatus.PENDING);
        assertThat(snapshot.clarificationStage()).isEqualTo("PLAN_REVIEW");
        assertThat(snapshot.steps()).isEmpty();
        assertThat(run.getClarificationAnswer()).isEqualTo("预算300元/人");
        assertThat(run.getPlanReviewJson()).isNull();
        assertThat(run.getResultReviewJson()).isNull();
        assertThat(run.getFinalOutput()).isNull();
        assertThat(run.getMermaidDiagram()).isNull();
        assertThat(scheduled).hasSize(1);
        verify(stepRepository).deleteByRunId("run-1");
    }

    @Test
    void resumeResultClarificationMarksCompletedStepsForRetryWithPreviousOutput() {
        TeamRunEntity run = run(TeamClarificationStage.RESULT_REVIEW);
        TeamStepEntity oldStep = step("step-1", TeamStepStatus.COMPLETED, "old raw");
        wireSnapshotData(run, List.of(oldStep));

        TeamRunSnapshot snapshot = service().resumeRun("team-1", "run-1", "人数改为20人");

        assertThat(snapshot.status()).isEqualTo(TeamRunStatus.PENDING);
        assertThat(snapshot.clarificationStage()).isEqualTo("RESULT_REVIEW");
        assertThat(snapshot.steps()).hasSize(1);
        TeamStepSnapshot retried = snapshot.steps().get(0);
        assertThat(retried.status()).isEqualTo(TeamStepStatus.RETRYING);
        assertThat(retried.revisionInstructions()).contains("人数改为20人");
        assertThat(json.stringList(oldStep.getPreviousOutputsJson())).containsExactly("old raw");
        assertThat(run.getResultReviewJson()).isNull();
        assertThat(run.getFinalOutput()).isNull();
        assertThat(run.getMermaidDiagram()).isNull();
        assertThat(scheduled).hasSize(1);
        verify(stepRepository, never()).deleteByRunId("run-1");
    }

    private TeamBlackboardService service() {
        TaskExecutor executor = scheduled::add;
        return new TeamBlackboardService(
            teamRepository,
            memberRepository,
            runRepository,
            stepRepository,
            eventRepository,
            agentFactory,
            null,
            executor
        );
    }

    private void wireSnapshotData(TeamRunEntity run, List<TeamStepEntity> steps) {
        List<TeamStepEntity> persistedSteps = new ArrayList<>(steps);
        TeamEntity team = new TeamEntity();
        team.setTeamId("team-1");
        team.setName("测试团队");
        when(teamRepository.findById("team-1")).thenReturn(Optional.of(team));
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(stepRepository.findByRunIdOrderByStepIndexAsc("run-1")).thenAnswer(invocation -> List.copyOf(persistedSteps));
        when(eventRepository.findByRunIdOrderByCreatedAtAscIdAsc("run-1")).thenReturn(List.of());
        when(memberRepository.findByTeamIdOrderBySortOrderAscIdAsc("team-1")).thenReturn(List.of(
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
        when(runRepository.save(any(TeamRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stepRepository.save(any(TeamStepEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            persistedSteps.clear();
            return null;
        }).when(stepRepository).deleteByRunId("run-1");
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
