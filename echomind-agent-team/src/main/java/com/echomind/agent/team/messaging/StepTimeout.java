package com.echomind.agent.team.messaging;

import java.time.Instant;

public record StepTimeout(String messageId, String runId, String stepId,
                          Instant createdAt, int retryCount,
                          long durationMs) implements TeamRunEvent {

    @Override
    public TeamMessage withRetryCount(int retries) {
        return new StepTimeout(messageId, runId, stepId, createdAt, retries, durationMs);
    }
}
