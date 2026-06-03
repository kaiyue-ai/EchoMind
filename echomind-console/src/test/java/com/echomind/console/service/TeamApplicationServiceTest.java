package com.echomind.console.service;

import com.echomind.agent.team.model.TeamMember;
import com.echomind.agent.team.runtime.TeamBlackboardService;
import com.echomind.agent.team.runtime.TeamEventSnapshot;
import com.echomind.agent.team.runtime.TeamMemberSpec;
import com.echomind.agent.team.runtime.TeamRunSnapshot;
import com.echomind.agent.team.runtime.TeamSnapshot;
import com.echomind.agent.team.runtime.TeamStepSnapshot;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.console.auth.AuthContext;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.dto.TeamCreateRequest;
import com.echomind.console.dto.TeamMemberRequest;
import com.echomind.console.dto.TeamRunCreateRequest;
import com.echomind.console.dto.TeamRunView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Team应用服务测试。
 *
 * <p>Team事实来源已经迁移到MySQL黑板服务；这里验证Console应用层只做请求转换和委托。</p>
 */
class TeamApplicationServiceTest {

    @Test
    void createTeamDelegatesMembersToBlackboardService() {
        TeamBlackboardService blackboard = mock(TeamBlackboardService.class);
        TeamApplicationService service = new TeamApplicationService(blackboard);
        TeamSnapshot snapshot = teamSnapshot();
        AuthContext.set(new AuthUser("user-a", "alice", true));
        when(blackboard.createTeam(eq("user-a"), eq("测试团队"), any())).thenReturn(snapshot);
        when(blackboard.listTeams("user-a")).thenReturn(List.of(snapshot));

        try {
            var created = service.createTeam(teamCreateRequest("测试团队"));

            assertThat(created.name()).isEqualTo("测试团队");
            assertThat(created.roles()).containsExactlyInAnyOrder("PLANNER", "EXECUTOR", "REVIEWER");
            assertThat(service.listTeams()).extracting("teamId").contains("team-1");
            verify(blackboard).createTeam(eq("user-a"), eq("测试团队"), any());
        } finally {
            AuthContext.clear();
        }
    }

    @Test
    void createRunReturnsAsyncRunView() {
        TeamBlackboardService blackboard = mock(TeamBlackboardService.class);
        TeamApplicationService service = new TeamApplicationService(blackboard);
        AuthContext.set(new AuthUser("user-a", "alice", true));
        when(blackboard.createRun("team-1", "user-a", "策划活动")).thenReturn(runSnapshot(TeamRunStatus.PENDING, null));

        try {
            TeamRunView run = service.createRun("team-1", new TeamRunCreateRequest("策划活动"));

            assertThat(run.teamId()).isEqualTo("team-1");
            assertThat(run.status()).isEqualTo("PENDING");
            verify(blackboard).createRun("team-1", "user-a", "策划活动");
        } finally {
            AuthContext.clear();
        }
    }

    @Test
    void deleteTeamDelegatesHardDeleteToBlackboardService() {
        TeamBlackboardService blackboard = mock(TeamBlackboardService.class);
        TeamApplicationService service = new TeamApplicationService(blackboard);
        AuthContext.set(new AuthUser("user-a", "alice", true));

        try {
            service.deleteTeam("team-1");

            verify(blackboard).deleteTeam("team-1", "user-a");
        } finally {
            AuthContext.clear();
        }
    }

    @Test
    void createTeamDelegatesExplicitReviewerMember() {
        TeamBlackboardService blackboard = mock(TeamBlackboardService.class);
        TeamApplicationService service = new TeamApplicationService(blackboard);
        AuthContext.set(new AuthUser("user-a", "alice", true));
        when(blackboard.createTeam(eq("user-a"), eq("团队"), any())).thenReturn(teamSnapshot());

        try {
            service.createTeam(teamCreateRequest("团队"));

            verify(blackboard).createTeam(eq("user-a"), eq("团队"), org.mockito.ArgumentMatchers.argThat(specs -> {
                @SuppressWarnings("unchecked")
                List<TeamMemberSpec> members = (List<TeamMemberSpec>) specs;
                return members.stream().anyMatch(member ->
                    member.role() == TeamRole.REVIEWER && member.agentId().equals("reviewer"));
            }));
        } finally {
            AuthContext.clear();
        }
    }

    private TeamCreateRequest teamCreateRequest(String name) {
        return new TeamCreateRequest(
            name,
            List.of(
                new TeamMemberRequest("planner", "PLANNER", List.of("planning"), 10),
                new TeamMemberRequest("executor", "EXECUTOR", List.of("general"), 20),
                new TeamMemberRequest("reviewer", "REVIEWER", List.of("review"), 30)
            )
        );
    }

    private TeamSnapshot teamSnapshot() {
        return new TeamSnapshot(
            "team-1",
            "user-a",
            "测试团队",
            List.of(
                member("planner", TeamRole.PLANNER),
                member("executor", TeamRole.EXECUTOR),
                member("reviewer", TeamRole.REVIEWER)
            ),
            Instant.now(),
            Instant.now()
        );
    }

    private TeamMember member(String agentId, TeamRole role) {
        return new TeamMember(agentId, agentId, role, List.of(role.name().toLowerCase()), 10, null);
    }

    private TeamRunSnapshot runSnapshot(TeamRunStatus status, String finalOutput) {
        return new TeamRunSnapshot(
            "run-1",
            "team-1",
            "default",
            "整理需求",
            status,
            "COMPLEX",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            finalOutput,
            "sequenceDiagram",
            0,
            0,
            0,
            0,
            0,
            List.<TeamStepSnapshot>of(),
            List.<TeamEventSnapshot>of(),
            Instant.now(),
            Instant.now()
        );
    }
}
