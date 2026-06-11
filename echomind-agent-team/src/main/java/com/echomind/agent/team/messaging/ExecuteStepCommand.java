package com.echomind.agent.team.messaging;

import java.time.Instant;

public record ExecuteStepCommand(String messageId, String runId, String stepId,
                                 Instant createdAt, int retryCount) implements TeamMessage {

    @Override
    public TeamMessage withRetryCount(int retries) {
        return new ExecuteStepCommand(messageId, runId, stepId, createdAt, retries);
    }
}
