package com.echomind.console.dto;

import java.util.List;

/**
 * 创建 Agent Team 的请求体。
 */
public record TeamCreateRequest(
    String name,
    String plannerId,
    String executorId,
    String reviewerId,
    List<TeamMemberRequest> members
) {
    public TeamCreateRequest(String name, String plannerId, String executorId, String reviewerId) {
        this(name, plannerId, executorId, reviewerId, null);
    }
}
