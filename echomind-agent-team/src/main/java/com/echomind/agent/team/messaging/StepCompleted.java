package com.echomind.agent.team.messaging;

import java.time.Instant;

public record StepCompleted(String messageId, String runId, String stepId,
                            Instant createdAt, int retryCount,
                            String output) implements TeamRunEvent {

    @Override
    public TeamMessage withRetryCount(int retries) {
        return new StepCompleted(messageId, runId, stepId, createdAt, retries, output);
    }
}
