package com.echomind.agent.team.messaging;

import java.time.Instant;

public record StepFailed(String messageId, String runId, String stepId,
                         Instant createdAt, int retryCount,
                         String errorMessage) implements TeamRunEvent {

    @Override
    public TeamMessage withRetryCount(int retries) {
        return new StepFailed(messageId, runId, stepId, createdAt, retries, errorMessage);
    }
}
