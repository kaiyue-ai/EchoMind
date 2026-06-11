package com.echomind.agent.team.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Sealed hierarchy for events dispatched to the Run Events sharded queue.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface TeamRunEvent extends TeamMessage
    permits RunStarted, StepCompleted, StepFailed,
            StepTimeout, RunPaused, RunResumed, RunCancelled {
}
