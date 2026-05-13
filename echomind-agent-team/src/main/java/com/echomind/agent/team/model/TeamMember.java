package com.echomind.agent.team.model;

import com.echomind.agent.Agent;
import com.echomind.agent.team.state.TeamRole;

import java.util.List;

/**
 * 运行时团队成员视图。
 */
public record TeamMember(
    String agentId,
    String agentName,
    TeamRole role,
    List<String> capabilityTags,
    int sortOrder,
    Agent agent
) {}
