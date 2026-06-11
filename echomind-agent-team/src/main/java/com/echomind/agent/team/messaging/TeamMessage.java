package com.echomind.agent.team.messaging;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

/**
 * Base interface for all Team RabbitMQ messages with idempotency and tracing metadata.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RunStarted.class, name = "RUN_STARTED"),
    @JsonSubTypes.Type(value = StepCompleted.class, name = "STEP_COMPLETED"),
    @JsonSubTypes.Type(value = StepFailed.class, name = "STEP_FAILED"),
    @JsonSubTypes.Type(value = StepTimeout.class, name = "STEP_TIMEOUT"),
    @JsonSubTypes.Type(value = RunPaused.class, name = "RUN_PAUSED"),
    @JsonSubTypes.Type(value = RunResumed.class, name = "RUN_RESUMED"),
    @JsonSubTypes.Type(value = RunCancelled.class, name = "RUN_CANCELLED"),
    @JsonSubTypes.Type(value = ExecuteStepCommand.class, name = "EXECUTE_STEP")
})
public sealed interface TeamMessage
    permits TeamRunEvent, ExecuteStepCommand {

    String messageId();

    String runId();

    String stepId();

    Instant createdAt();

    int retryCount();

    TeamMessage withRetryCount(int retries);
}
