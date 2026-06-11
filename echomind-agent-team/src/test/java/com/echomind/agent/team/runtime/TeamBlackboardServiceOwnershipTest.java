package com.echomind.agent.team.runtime;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.store.TeamEntity;
import com.echomind.agent.team.store.TeamEventMapper;
import com.echomind.agent.team.store.TeamMemberEntity;
import com.echomind.agent.team.store.TeamMemberMapper;
import com.echomind.agent.team.store.TeamMapper;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamRunMapper;
import com.echomind.agent.team.store.TeamStepMapper;
import org.junit.jupiter.api.Test;


import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamBlackboardServiceOwnershipTest {

    private final TeamMapper teamMapper = mock(TeamMapper.class);
    private final TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
    private final TeamRunMapper runMapper = mock(TeamRunMapper.class);
    private final TeamStepMapper stepMapper = mock(TeamStepMapper.class);
    private final TeamEventMapper eventMapper = mock(TeamEventMapper.class);
    private final AgentFactory agentFactory = mock(AgentFactory.class);

    @Test
    void listTeamsOnlyReturnsTeamsOwnedByUser() {
        TeamEntity userTeam = team("team-a", "user-a");
        Agent executor = agent("executor");
        when(teamMapper.selectByOwnerUserIdOrderByCreatedAtAsc("user-a")).thenReturn(List.of(userTeam));
        when(memberMapper.selectByTeamIdOrderBySortOrderAscIdAsc("team-a")).thenReturn(List.of(member("team-a")));
        when(agentFactory.get("executor")).thenReturn(executor);

        List<TeamSnapshot> teams = service().listTeams("user-a");

        assertThat(teams).extracting(TeamSnapshot::teamId).containsExactly("team-a");
        assertThat(teams).extracting(TeamSnapshot::ownerUserId).containsExactly("user-a");
        verify(teamMapper).selectByOwnerUserIdOrderByCreatedAtAsc("user-a");
    }

    @Test
    void createRunRejectsTeamOwnedByAnotherUser() {
        when(teamMapper.selectOptionalByTeamIdAndOwnerUserId("team-a", "user-b")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createRun("team-a", "user-b", "执行任务"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Team not found");
    }

    @Test
    void getRunRejectsEvenWhenRunIdBelongsToDifferentUser() {
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId("run-a");
        run.setTeamId("team-a");
        run.setUserId("user-a");
        when(runMapper.selectOptionalById("run-a")).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service().getRun("team-a", "user-b", "run-a"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Run does not belong to current user");
    }

    @Test
    void getRunRejectsWhenRunUserMatchesButTeamOwnerDoesNot() {
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId("run-a");
        run.setTeamId("team-a");
        run.setUserId("user-a");
        when(runMapper.selectOptionalById("run-a")).thenReturn(Optional.of(run));
        when(teamMapper.selectOptionalByTeamIdAndOwnerUserId("team-a", "user-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getRun("team-a", "user-a", "run-a"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Team not found");
    }

    private TeamBlackboardService service() {
        return new TeamBlackboardService(
            teamMapper,
            memberMapper,
            runMapper,
            stepMapper,
            eventMapper,
            agentFactory,
            mock(AgentOrchestrator.class)
        );
    }

    private TeamEntity team(String teamId, String ownerUserId) {
        TeamEntity team = new TeamEntity();
        team.setTeamId(teamId);
        team.setOwnerUserId(ownerUserId);
        team.setName("测试团队");
        return team;
    }

    private TeamMemberEntity member(String teamId) {
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(teamId);
        member.setAgentId("executor");
        member.setRole(TeamRole.EXECUTOR);
        member.setCapabilityTagsJson("[]");
        return member;
    }

    private Agent agent(String agentId) {
        Agent agent = mock(Agent.class);
        when(agent.getAgentId()).thenReturn(agentId);
        when(agent.getName()).thenReturn(agentId);
        return agent;
    }
}
