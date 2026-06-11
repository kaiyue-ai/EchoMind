package com.echomind.agent.team.messaging;

import java.time.Instant;

public record RunStarted(String messageId, String runId, String stepId,
                         Instant createdAt, int retryCount,
                         String teamId) implements TeamRunEvent {

    @Override
    public TeamMessage withRetryCount(int retries) {
        return new RunStarted(messageId, runId, null, createdAt, retries, teamId);
    }
}
