package com.echomind.agent.team.runtime;

import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.store.TeamStepEntity;

/**
 * MySQL 黑板里的 Step 执行结果，是消费者决定后续 DAG 事件的事实来源。
 */
public record StepExecutionOutcome(
    String stepId,
    TeamStepStatus stepStatus,
    TeamRunStatus runStatus,
    String output,
    int retryCount,
    boolean missing
) {

    public static StepExecutionOutcome from(TeamStepEntity step, TeamRunStatus runStatus) {
        return new StepExecutionOutcome(
            step.getStepId(),
            step.getStatus(),
            runStatus,
            step.getRawOutput(),
            step.getRetryCount(),
            false
        );
    }

    public static StepExecutionOutcome missing(String stepId) {
        return new StepExecutionOutcome(stepId, null, null, "", 0, true);
    }

    public boolean completed() {
        return stepStatus == TeamStepStatus.COMPLETED;
    }

    public boolean failed() {
        return stepStatus == TeamStepStatus.FAILED || missing;
    }

    public boolean retrying() {
        return stepStatus == TeamStepStatus.RETRYING;
    }

    public boolean runTerminal() {
        return runStatus == TeamRunStatus.COMPLETED
            || runStatus == TeamRunStatus.FAILED;
    }
}
