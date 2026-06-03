package com.echomind.agent.team.runtime;

import com.echomind.agent.team.model.TeamMember;

import java.time.Instant;
import java.util.List;

/**
 * Team 配置的只读快照。
 */
public record TeamSnapshot(
    String teamId,// 团队id
    String ownerUserId,// 团队所属用户
    String name,//团队名称
    List<TeamMember> members,//团队成员
    Instant createdAt,// 创建时间
    Instant updatedAt// 更新时间
) {}
