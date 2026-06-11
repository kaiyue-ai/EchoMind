package com.echomind.agent.team.messaging;

import java.time.Instant;

public record RunPaused(String messageId, String runId, String stepId,
                        Instant createdAt, int retryCount,
                        String stage, String question) implements TeamRunEvent {

    @Override
    public TeamMessage withRetryCount(int retries) {
        return new RunPaused(messageId, runId, null, createdAt, retries, stage, question);
    }
}
