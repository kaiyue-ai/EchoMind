package com.echomind.console.dto;

import com.echomind.agent.team.AgentTeam;
import com.echomind.agent.team.runtime.TeamSnapshot;

import java.util.List;

/**
 * Agent Team 的接口视图。
 */
public record TeamView(
    String teamId,
    String name,
    List<String> roles,
    List<TeamMemberView> members
) {
    public static TeamView from(AgentTeam team) {
        return new TeamView(
            team.getTeamId(),
            team.getName(),
            team.getRoles().stream().map(Enum::name).toList(),
            List.of()
        );
    }

    public static TeamView from(TeamSnapshot team) {
        List<TeamMemberView> members = team.members().stream()
            .map(TeamMemberView::from)
            .toList();
        return new TeamView(
            team.teamId(),
            team.name(),
            members.stream().map(TeamMemberView::role).distinct().toList(),
            members
        );
    }
}
