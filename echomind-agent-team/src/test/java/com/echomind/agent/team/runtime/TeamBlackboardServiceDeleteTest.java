package com.echomind.agent.team.runtime;

import com.echomind.agent.AgentFactory;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.team.store.TeamEntity;
import com.echomind.agent.team.store.TeamEventRepository;
import com.echomind.agent.team.store.TeamMemberRepository;
import com.echomind.agent.team.store.TeamRepository;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamRunRepository;
import com.echomind.agent.team.store.TeamStepRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamBlackboardServiceDeleteTest {

    private final TeamRepository teamRepository = mock(TeamRepository.class);
    private final TeamMemberRepository memberRepository = mock(TeamMemberRepository.class);
    private final TeamRunRepository runRepository = mock(TeamRunRepository.class);
    private final TeamStepRepository stepRepository = mock(TeamStepRepository.class);
    private final TeamEventRepository eventRepository = mock(TeamEventRepository.class);

    @Test
    void deleteTeamHardDeletesBlackboardRowsBeforeTeam() {
        TeamEntity team = new TeamEntity();
        team.setTeamId("team-1");
        team.setName("测试团队");
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId("run-1");
        run.setTeamId("team-1");
        when(teamRepository.findById("team-1")).thenReturn(Optional.of(team));
        when(runRepository.findByTeamIdOrderByCreatedAtDesc("team-1")).thenReturn(List.of(run));

        service().deleteTeam("team-1");

        var order = inOrder(eventRepository, stepRepository, runRepository, memberRepository, teamRepository);
        order.verify(eventRepository).deleteByRunIdIn(List.of("run-1"));
        order.verify(stepRepository).deleteByRunIdIn(List.of("run-1"));
        order.verify(runRepository).deleteByTeamId("team-1");
        order.verify(memberRepository).deleteByTeamId("team-1");
        order.verify(teamRepository).delete(team);
    }

    @Test
    void deleteTeamSkipsRunScopedDeletesWhenTeamHasNoRuns() {
        TeamEntity team = new TeamEntity();
        team.setTeamId("team-1");
        team.setName("测试团队");
        when(teamRepository.findById("team-1")).thenReturn(Optional.of(team));
        when(runRepository.findByTeamIdOrderByCreatedAtDesc("team-1")).thenReturn(List.of());

        service().deleteTeam("team-1");

        verify(eventRepository, never()).deleteByRunIdIn(List.of());
        verify(stepRepository, never()).deleteByRunIdIn(List.of());
        verify(runRepository).deleteByTeamId("team-1");
        verify(memberRepository).deleteByTeamId("team-1");
        verify(teamRepository).delete(team);
    }

    @Test
    void recordEventSkipsDeletedRunAfterTeamHardDelete() {
        TeamEntity team = new TeamEntity();
        team.setTeamId("team-1");
        team.setName("测试团队");
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId("run-1");
        run.setTeamId("team-1");
        when(teamRepository.findById("team-1")).thenReturn(Optional.of(team));
        when(runRepository.findByTeamIdOrderByCreatedAtDesc("team-1")).thenReturn(List.of(run));
        TeamBlackboardService service = service();

        service.deleteTeam("team-1");
        service.recordEvent("run-1", null, com.echomind.agent.team.state.TeamEventType.RUN_COMPLETED,
            null, null, "late event", null);

        verify(eventRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deletedRunCannotBeWrittenAfterHardDelete() {
        TeamEntity team = new TeamEntity();
        team.setTeamId("team-1");
        team.setName("测试团队");
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId("run-1");
        run.setTeamId("team-1");
        when(teamRepository.findById("team-1")).thenReturn(Optional.of(team));
        when(runRepository.findByTeamIdOrderByCreatedAtDesc("team-1")).thenReturn(List.of(run));
        TeamBlackboardService service = service();

        service.deleteTeam("team-1");

        assertThatThrownBy(() -> service.executeRun("run-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Run not found");
    }

    private TeamBlackboardService service() {
        return new TeamBlackboardService(
            teamRepository,
            memberRepository,
            runRepository,
            stepRepository,
            eventRepository,
            mock(AgentFactory.class),
            mock(AgentOrchestrator.class),
            new SyncTaskExecutor()
        );
    }
}
