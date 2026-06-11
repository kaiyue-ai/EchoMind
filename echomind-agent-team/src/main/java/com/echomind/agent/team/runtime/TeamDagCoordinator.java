package com.echomind.agent.team.runtime;

import com.echomind.agent.team.messaging.RunCancelled;
import com.echomind.agent.team.messaging.RunPaused;
import com.echomind.agent.team.messaging.RunResumed;
import com.echomind.agent.team.messaging.RunStarted;
import com.echomind.agent.team.messaging.StepCompleted;
import com.echomind.agent.team.messaging.StepFailed;
import com.echomind.agent.team.messaging.StepTimeout;
import com.echomind.agent.team.messaging.TeamRunEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * DAG coordinator that processes run events in order (guaranteed by per-run sharding).
 * Runs in the Run Events consumer thread and orchestrates
 * dependency resolution, slot management, and step dispatch.
 */
@Slf4j
public class TeamDagCoordinator {

    private final TeamRedisDagStore dagStore;
    private final TeamStepCommandProducer producer;
    private final TeamBlackboardService blackboard;

    public TeamDagCoordinator(TeamRedisDagStore dagStore,
                              TeamStepCommandProducer producer,
                              TeamBlackboardService blackboard) {
        this.dagStore = dagStore;
        this.producer = producer;
        this.blackboard = blackboard;
    }

    /**
     * Dispatch a run event to the appropriate handler.
     */
    public void handle(String runId, TeamRunEvent event) {
        log.debug("Coordinator received event type={} runId={} messageId={}",
            event.getClass().getSimpleName(), runId, event.messageId());

        if (event instanceof RunStarted rs) {
            onRunStarted(rs);
        } else if (event instanceof StepCompleted sc) {
            onStepCompleted(sc);
        } else if (event instanceof StepFailed sf) {
            onStepFailed(sf);
        } else if (event instanceof StepTimeout st) {
            onStepTimeout(st);
        } else if (event instanceof RunPaused rp) {
            onRunPaused(rp);
        } else if (event instanceof RunResumed rr) {
            onRunResumed(rr);
        } else if (event instanceof RunCancelled rc) {
            onRunCancelled(rc);
        }
    }

    // ---- Event Handlers ----

    void onRunStarted(RunStarted event) {
        String runId = event.runId();
        log.info("DAG coordinator starting run {}", runId);
        // planAndReview creates steps in MySQL, then initializes Redis DAG
        blackboard.planAndReviewForCoordinator(runId);
        dispatchReadySteps(runId);
    }

    void onStepCompleted(StepCompleted event) {
        String runId = event.runId();
        String stepId = event.stepId();

        List<String> newlyReady = dagStore.completeStepAndCascade(runId, stepId, event.output());
        log.debug("Step {} completed, {} newly ready steps: {}", stepId, newlyReady.size(), newlyReady);

        // Save output to Redis for dependency context
        dagStore.getStepOutput(runId, stepId); // output already saved by Lua script

        dispatchReadySteps(runId);

        if (dagStore.isDagComplete(runId)) {
            log.info("DAG complete for run {}", runId);
            blackboard.onDagCompleteInCoordinator(runId);
        }
    }

    void onStepFailed(StepFailed event) {
        String runId = event.runId();
        String stepId = event.stepId();

        int retryCount = dagStore.getStepRetryCount(runId, stepId);
        int maxRetries = blackboard.getMaxStepRetries();

        if (retryCount < maxRetries) {
            // Retry: set status back to RETRYING, re-queue as READY
            dagStore.setStepRetrying(runId, stepId, retryCount + 1);
            dagStore.markStepReady(runId, stepId);
            log.info("Step {} failed, retrying ({}/{})", stepId, retryCount + 1, maxRetries);
            dispatchReadySteps(runId);
        } else {
            // Retries exhausted
            dagStore.releaseSlot(runId, stepId);
            log.warn("Step {} failed after {} retries, failing run {}", stepId, retryCount, runId);
            blackboard.failRunFromCoordinator(runId,
                "Step " + stepId + " failed after " + retryCount + " retries: " + event.errorMessage());
        }
    }

    void onStepTimeout(StepTimeout event) {
        String runId = event.runId();
        String stepId = event.stepId();

        int retryCount = dagStore.getStepRetryCount(runId, stepId);
        int maxRetries = blackboard.getMaxStepRetries();

        if (retryCount < maxRetries) {
            dagStore.setStepRetrying(runId, stepId, retryCount + 1);
            dagStore.markStepReady(runId, stepId);
            log.info("Step {} timed out after {}ms, retrying ({}/{})",
                stepId, event.durationMs(), retryCount + 1, maxRetries);
            dispatchReadySteps(runId);
        } else {
            dagStore.releaseSlot(runId, stepId);
            log.warn("Step {} timed out after {} retries, failing run {}", stepId, retryCount, runId);
            blackboard.failRunFromCoordinator(runId,
                "Step " + stepId + " timed out after " + retryCount + " retries");
        }
    }

    void onRunPaused(RunPaused event) {
        String runId = event.runId();
        dagStore.setControlFlag(runId, "stopping", event.stage());
        log.info("Run {} paused for clarification: {}", runId, event.question());
    }

    void onRunResumed(RunResumed event) {
        String runId = event.runId();
        dagStore.clearControlFlag(runId, "stopping");
        log.info("Run {} resumed", runId);

        // Re-dispatch any pending ready steps
        dispatchReadySteps(runId);

        // If all done and nothing running, trigger completion
        if (dagStore.isDagComplete(runId)) {
            blackboard.onDagCompleteInCoordinator(runId);
        }
    }

    void onRunCancelled(RunCancelled event) {
        String runId = event.runId();
        dagStore.setControlFlag(runId, "stopping", "CANCELLED");
        dagStore.setDagStatus(runId, "FAILED");
        log.info("Run {} cancelled", runId);
        blackboard.failRunFromCoordinator(runId, "Run cancelled");
    }

    // ---- Step Dispatch ----

    void dispatchReadySteps(String runId) {
        // Check if run is stopping
        String stopping = dagStore.getControlFlag(runId, "stopping");
        if (stopping != null) {
            log.debug("Run {} is stopping ({}), not dispatching new steps", runId, stopping);
            return;
        }

        while (dagStore.hasPendingReady(runId)) {
            String claimed = dagStore.tryClaimSlot(runId);
            if (claimed == null) {
                // No slot available (at max concurrent)
                break;
            }
            log.debug("Dispatching step {} for run {}", claimed, runId);
            producer.publishExecuteStep(runId, claimed);
        }
    }
}
