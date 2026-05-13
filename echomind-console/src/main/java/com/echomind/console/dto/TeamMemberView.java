package com.echomind.console.dto;

import com.echomind.agent.team.model.TeamMember;

import java.util.List;

/**
 * Agent Team 成员展示视图。
 */
public record TeamMemberView(
    String agentId,
    String agentName,
    String role,
    List<String> capabilityTags,
    int sortOrder
) {
    public static TeamMemberView from(TeamMember member) {
        return new TeamMemberView(
            member.agentId(),
            member.agentName(),
            member.role().name(),
            member.capabilityTags(),
            member.sortOrder()
        );
    }
}
