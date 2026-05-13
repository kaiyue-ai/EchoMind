package com.echomind.console.dto;

import com.echomind.agent.team.runtime.TeamEventSnapshot;

import java.time.Instant;

/**
 * Team 黑板事件流视图。
 */
public record TeamEventView(
    Long id,
    String runId,
    String stepId,
    String type,
    String actorRole,
    String actorAgentId,
    String message,
    String payloadJson,
    Instant createdAt
) {
    public static TeamEventView from(TeamEventSnapshot event) {
        return new TeamEventView(
            event.id(),
            event.runId(),
            event.stepId(),
            event.type().name(),
            event.actorRole() == null ? null : event.actorRole().name(),
            event.actorAgentId(),
            event.message(),
            event.payloadJson(),
            event.createdAt()
        );
    }
}
