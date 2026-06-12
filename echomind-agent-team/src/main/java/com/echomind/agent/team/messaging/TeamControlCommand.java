package com.echomind.agent.team.messaging;

import java.time.Instant;

public record TeamControlCommand(
    String messageId,
    String runId,
    String stepId,
    Instant createdAt,
    int retryCount,
    TeamControlAction action
) implements TeamMessage {

    @Override
    public TeamMessage withRetryCount(int retries) {
        return new TeamControlCommand(messageId, runId, stepId, createdAt, retries, action);
    }
}
