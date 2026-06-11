package com.echomind.agent.team.messaging;

import java.time.Instant;

public record RunCancelled(String messageId, String runId, String stepId,
                           Instant createdAt, int retryCount) implements TeamRunEvent {

    @Override
    public TeamMessage withRetryCount(int retries) {
        return new RunCancelled(messageId, runId, null, createdAt, retries);
    }
}
