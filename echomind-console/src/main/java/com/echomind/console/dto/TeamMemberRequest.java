package com.echomind.console.dto;

import java.util.List;

/**
 * 创建 Agent Team 时提交的成员配置。
 */
public record TeamMemberRequest(
    String agentId,
    String role,
    List<String> capabilityTags,
    Integer sortOrder
) {}
