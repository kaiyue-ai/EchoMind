package com.echomind.console.dto;

import java.util.List;

/**
 * 创建 Agent Team 的请求体。
 */
public record TeamCreateRequest(
    String name,
    List<TeamMemberRequest> members
) {}
