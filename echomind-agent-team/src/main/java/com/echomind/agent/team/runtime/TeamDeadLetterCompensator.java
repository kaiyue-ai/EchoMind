package com.echomind.agent.team.runtime;

import com.echomind.agent.team.messaging.ExecuteStepCommand;
import com.echomind.agent.team.messaging.RunStarted;
import com.echomind.agent.team.messaging.TeamMessage;
import com.echomind.agent.team.messaging.TeamRunEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Compensation logic for Team messages that land in the Dead Letter Queue.
 *
 * <p>Called by {@code RabbitDeadLetterService} when archiving team DLQ messages.
 * Compensation ensures the DAG doesn't hang waiting for a step that will never execute.
 */
@Slf4j
public class TeamDeadLetterCompensator {

    private final TeamBlackboardService blackboard;

    public TeamDeadLetterCompensator(TeamBlackboardService blackboard) {
        this.blackboard = blackboard;
    }

    /**
     * Execute compensation for a dead-lettered team message.
     *
     * @param msg     the deserialized team message
     * @param dlqName the DLQ the message came from
     */
    public void compensate(TeamMessage msg, String dlqName) {
        log.warn("Compensating dead-lettered team message: type={} runId={} stepId={} dlq={}",
            msg.getClass().getSimpleName(), msg.runId(), msg.stepId(), dlqName);

        if (msg instanceof ExecuteStepCommand cmd) {
            compensateStepCommand(cmd);
        } else if (msg instanceof RunStarted rs) {
            compensateRunStarted(rs);
        } else if (msg instanceof TeamRunEvent event) {
            compensateRunEvent(event);
        } else {
            log.warn("Unknown team message type in DLQ: {}", msg.getClass().getName());
        }
    }

    private void compensateStepCommand(ExecuteStepCommand cmd) {
        // Step execution never happened → mark it FAILED so DAG can progress or fail
        try {
            blackboard.markStepFailedFromCompensator(cmd.runId(), cmd.stepId(),
                "Message dead-lettered after retry exhaustion");
            log.info("Compensated ExecuteStepCommand: marked step {} as FAILED", cmd.stepId());
        } catch (Exception e) {
            log.error("Failed to compensate ExecuteStepCommand for step {}", cmd.stepId(), e);
        }
    }

    private void compensateRunStarted(RunStarted event) {
        // Run couldn't start → mark run as FAILED
        try {
            blackboard.failRunFromCoordinator(event.runId(),
                "RunStart message dead-lettered, cannot start run");
            log.info("Compensated RunStarted: marked run {} as FAILED", event.runId());
        } catch (Exception e) {
            log.error("Failed to compensate RunStarted for run {}", event.runId(), e);
        }
    }

    private void compensateRunEvent(TeamRunEvent event) {
        // Run event dead-lettered — do NOT automatically compensate.
        // The DAG state may be inconsistent; requires human investigation.
        log.warn("Run event dead-lettered, manual intervention required: type={} runId={} messageId={}",
            event.getClass().getSimpleName(), event.runId(), event.messageId());
    }
}
