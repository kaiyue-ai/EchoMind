package com.echomind.agent.team.model;

import com.echomind.agent.Agent;
import com.echomind.agent.team.state.TeamRole;

import java.util.List;

/**
 * 运行时团队成员视图。
 */
public record TeamMember(
    String agentId, // agentId
    String agentName, // agentName
    TeamRole role, // 角色
    List<String> capabilityTags, // 能力标签
    int sortOrder, // 分类
    Agent agent // agent
) {}
