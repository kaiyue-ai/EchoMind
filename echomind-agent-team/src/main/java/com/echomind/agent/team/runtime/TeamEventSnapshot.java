package com.echomind.agent.team.runtime;

import com.echomind.agent.team.state.TeamEventType;
import com.echomind.agent.team.state.TeamRole;

import java.time.Instant;

/**
 * Team 事件流的接口快照。
 */
public record TeamEventSnapshot(
    Long id,
    String runId,
    String stepId,
    TeamEventType type,
    TeamRole actorRole,
    String actorAgentId,
    String message,
    String payloadJson,
    Instant createdAt
) {}
