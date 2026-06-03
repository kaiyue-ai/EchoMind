package com.echomind.agent.team.runtime;

import com.echomind.agent.team.state.TeamRole;

import java.util.List;

/**
 * 创建或更新团队成员的输入规格。
 */
public record TeamMemberSpec(
    String agentId, // 执行器id
    TeamRole role, // 角色
    List<String> capabilityTags, // 能力标签
    int sortOrder // 排序顺序
) {}
