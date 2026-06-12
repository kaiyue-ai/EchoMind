package com.echomind.agent.team.runtime;

import com.echomind.agent.team.messaging.StepFailed;
import com.echomind.agent.team.messaging.StepTimeout;
import com.echomind.agent.team.messaging.RunStarted;
import com.echomind.agent.team.messaging.StepCompleted;
import com.echomind.agent.team.messaging.TeamControlAction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamDagCoordinatorTest {

    private final TeamRedisDagStore dagStore = mock(TeamRedisDagStore.class);
    private final TeamStepCommandProducer producer = mock(TeamStepCommandProducer.class);
    private final TeamBlackboardService blackboard = mock(TeamBlackboardService.class);
    private final TeamDagCoordinator coordinator = new TeamDagCoordinator(dagStore, producer, blackboard);

    @Test
    void runStartedDispatchesPlanningControlCommandInsteadOfRunningPlannerInline() {
        coordinator.onRunStarted(new RunStarted(
            UUID.randomUUID().toString(), "run-1", null, Instant.now(), 0, "team-1"
        ));

        verify(producer).publishControl("run-1", TeamControlAction.PLAN_AND_REVIEW);
        verify(blackboard, never()).planAndReviewForCoordinator("run-1");
    }

    @Test
    void dagCompleteDispatchesMergeControlCommandInsteadOfMergingInline() {
        when(dagStore.completeStepAndCascade("run-1", "step-1", "out")).thenReturn(java.util.List.of());
        when(dagStore.isDagComplete("run-1")).thenReturn(true);

        coordinator.onStepCompleted(new StepCompleted(
            UUID.randomUUID().toString(), "run-1", "step-1", Instant.now(), 0, "out"
        ));

        verify(producer).publishControl("run-1", TeamControlAction.DAG_COMPLETE);
        verify(blackboard, never()).onDagCompleteInCoordinator("run-1");
    }

    @Test
    void stepFailureRetryReleasesRunningSlotBeforeRequeue() {
        when(dagStore.getStepRetryCount("run-1", "step-1")).thenReturn(0);
        when(blackboard.getMaxStepRetries()).thenReturn(2);

        coordinator.onStepFailed(new StepFailed(
            UUID.randomUUID().toString(), "run-1", "step-1", Instant.now(), 0, "boom"
        ));

        verify(dagStore).releaseSlotForRetry("run-1", "step-1", 1);
        verify(dagStore).markStepReady("run-1", "step-1");
        verify(dagStore, never()).setStepRetrying("run-1", "step-1", 1);
        verify(blackboard).markStepRetryingFromCoordinator("run-1", "step-1", 1, "boom");
    }

    @Test
    void stepTimeoutRetryReleasesRunningSlotBeforeRequeue() {
        when(dagStore.getStepRetryCount("run-1", "step-1")).thenReturn(1);
        when(blackboard.getMaxStepRetries()).thenReturn(2);

        coordinator.onStepTimeout(new StepTimeout(
            UUID.randomUUID().toString(), "run-1", "step-1", Instant.now(), 0, 5000
        ));

        verify(dagStore).releaseSlotForRetry("run-1", "step-1", 2);
        verify(dagStore).markStepReady("run-1", "step-1");
        verify(dagStore, never()).setStepRetrying("run-1", "step-1", 2);
        verify(blackboard).markStepRetryingFromCoordinator("run-1", "step-1", 2,
            "步骤超时，耗时 5000ms");
    }
}
