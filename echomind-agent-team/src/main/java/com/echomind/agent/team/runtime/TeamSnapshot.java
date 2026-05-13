package com.echomind.agent.team.runtime;

import com.echomind.agent.team.model.TeamMember;

import java.time.Instant;
import java.util.List;

/**
 * Team 配置的只读快照。
 */
public record TeamSnapshot(
    String teamId,
    String name,
    List<TeamMember> members,
    Instant createdAt,
    Instant updatedAt
) {}
