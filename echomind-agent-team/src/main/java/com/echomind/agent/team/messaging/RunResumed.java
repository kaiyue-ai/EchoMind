package com.echomind.agent.team.messaging;

import java.time.Instant;

public record RunResumed(String messageId, String runId, String stepId,
                         Instant createdAt, int retryCount,
                         String answer) implements TeamRunEvent {

    @Override
    public TeamMessage withRetryCount(int retries) {
        return new RunResumed(messageId, runId, null, createdAt, retries, answer);
    }
}
