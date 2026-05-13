package com.echomind.agent.team.runtime;

import com.echomind.agent.team.state.TeamRole;

import java.util.List;

/**
 * 创建或更新团队成员的输入规格。
 */
public record TeamMemberSpec(
    String agentId,
    TeamRole role,
    List<String> capabilityTags,
    int sortOrder
) {}
