package com.echomind.agent.team.runtime;

import com.echomind.agent.AgentFactory;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.team.store.TeamEntity;
import com.echomind.agent.team.store.TeamEventMapper;
import com.echomind.agent.team.store.TeamMemberMapper;
import com.echomind.agent.team.store.TeamMapper;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamRunMapper;
import com.echomind.agent.team.store.TeamStepMapper;
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

    private final TeamMapper teamMapper = mock(TeamMapper.class);
    private final TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
    private final TeamRunMapper runMapper = mock(TeamRunMapper.class);
    private final TeamStepMapper stepMapper = mock(TeamStepMapper.class);
    private final TeamEventMapper eventMapper = mock(TeamEventMapper.class);

    @Test
    void deleteTeamHardDeletesBlackboardRowsBeforeTeam() {
        TeamEntity team = new TeamEntity();
        team.setTeamId("team-1");
        team.setOwnerUserId("user-a");
        team.setName("测试团队");
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId("run-1");
        run.setTeamId("team-1");
        when(teamMapper.selectOptionalByTeamIdAndOwnerUserId("team-1", "user-a")).thenReturn(Optional.of(team));
        when(runMapper.selectByTeamIdOrderByCreatedAtDesc("team-1")).thenReturn(List.of(run));

        service().deleteTeam("team-1", "user-a");

        var order = inOrder(eventMapper, stepMapper, runMapper, memberMapper, teamMapper);
        order.verify(eventMapper).deleteByRunIdIn(List.of("run-1"));
        order.verify(stepMapper).deleteByRunIdIn(List.of("run-1"));
        order.verify(runMapper).deleteByTeamId("team-1");
        order.verify(memberMapper).deleteByTeamId("team-1");
        order.verify(teamMapper).deleteEntity(team);
    }

    @Test
    void deleteTeamSkipsRunScopedDeletesWhenTeamHasNoRuns() {
        TeamEntity team = new TeamEntity();
        team.setTeamId("team-1");
        team.setOwnerUserId("user-a");
        team.setName("测试团队");
        when(teamMapper.selectOptionalByTeamIdAndOwnerUserId("team-1", "user-a")).thenReturn(Optional.of(team));
        when(runMapper.selectByTeamIdOrderByCreatedAtDesc("team-1")).thenReturn(List.of());

        service().deleteTeam("team-1", "user-a");

        verify(eventMapper, never()).deleteByRunIdIn(List.of());
        verify(stepMapper, never()).deleteByRunIdIn(List.of());
        verify(runMapper).deleteByTeamId("team-1");
        verify(memberMapper).deleteByTeamId("team-1");
        verify(teamMapper).deleteEntity(team);
    }

    @Test
    void recordEventSkipsDeletedRunAfterTeamHardDelete() {
        TeamEntity team = new TeamEntity();
        team.setTeamId("team-1");
        team.setOwnerUserId("user-a");
        team.setName("测试团队");
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId("run-1");
        run.setTeamId("team-1");
        when(teamMapper.selectOptionalByTeamIdAndOwnerUserId("team-1", "user-a")).thenReturn(Optional.of(team));
        when(runMapper.selectByTeamIdOrderByCreatedAtDesc("team-1")).thenReturn(List.of(run));
        TeamBlackboardService service = service();

        service.deleteTeam("team-1", "user-a");
        service.recordEvent("run-1", null, com.echomind.agent.team.state.TeamEventType.RUN_COMPLETED,
            null, null, "late event", null);

        verify(eventMapper, never()).upsertById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deletedRunCannotBeWrittenAfterHardDelete() {
        TeamEntity team = new TeamEntity();
        team.setTeamId("team-1");
        team.setOwnerUserId("user-a");
        team.setName("测试团队");
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId("run-1");
        run.setTeamId("team-1");
        when(teamMapper.selectOptionalByTeamIdAndOwnerUserId("team-1", "user-a")).thenReturn(Optional.of(team));
        when(runMapper.selectByTeamIdOrderByCreatedAtDesc("team-1")).thenReturn(List.of(run));
        TeamBlackboardService service = service();

        service.deleteTeam("team-1", "user-a");

        service.executeRun("run-1");

        verify(runMapper, never()).upsertById(org.mockito.ArgumentMatchers.any());
        verify(stepMapper, never()).upsertById(org.mockito.ArgumentMatchers.any());
        verify(eventMapper, never()).upsertById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deleteTeamRejectsDifferentOwner() {
        when(teamMapper.selectOptionalByTeamIdAndOwnerUserId("team-1", "user-b")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().deleteTeam("team-1", "user-b"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Team not found");

        verify(runMapper, never()).deleteByTeamId("team-1");
        verify(memberMapper, never()).deleteByTeamId("team-1");
    }

    private TeamBlackboardService service() {
        return new TeamBlackboardService(
            teamMapper,
            memberMapper,
            runMapper,
            stepMapper,
            eventMapper,
            mock(AgentFactory.class),
            mock(AgentOrchestrator.class),
            new SyncTaskExecutor()
        );
    }
}
