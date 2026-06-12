package com.echomind.agent.team.runtime;

import com.echomind.agent.team.messaging.ExecuteStepCommand;
import com.echomind.agent.team.messaging.StepCompleted;
import com.echomind.agent.team.messaging.StepFailed;
import com.echomind.agent.team.messaging.TeamRunEvent;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.store.TeamStepEntity;
import com.echomind.agent.team.store.TeamStepMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamStepExecutionConsumerTest {

    private final TeamBlackboardService blackboard = mock(TeamBlackboardService.class);
    private final TeamStepCommandProducer producer = mock(TeamStepCommandProducer.class);
    private final TeamRedisDagStore dagStore = mock(TeamRedisDagStore.class);
    private final TeamStepMapper stepMapper = mock(TeamStepMapper.class);
    private final TeamRuntimeProperties properties = new TeamRuntimeProperties();
    private final TeamStepExecutionConsumer consumer =
        new TeamStepExecutionConsumer(blackboard, producer, dagStore, stepMapper, properties);
    private final Channel channel = mock(Channel.class);
    private final ExecuteStepCommand command =
        new ExecuteStepCommand("msg-1", "run-1", "step-1", Instant.now(), 0);

    @Test
    void pendingMysqlStepDoesNotPublishCompletedEvent() throws Exception {
        properties.setStepTimeoutSeconds(0);
        when(stepMapper.selectById("step-1")).thenReturn(step(TeamStepStatus.PENDING));

        consumer.onExecuteStep(command, channel, 42L, List.of());

        verify(blackboard, never()).executeStepPublic(any(), any());
        ArgumentCaptor<TeamRunEvent> eventCaptor = ArgumentCaptor.forClass(TeamRunEvent.class);
        verify(producer).publishRunEvent(eq("run-1"), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(StepFailed.class);
        assertThat(((StepFailed) eventCaptor.getValue()).errorMessage())
            .contains("不可执行");
        verify(channel).basicAck(42L, false);
    }

    @Test
    void completedMysqlStepPublishesCompletedEvent() throws Exception {
        properties.setStepTimeoutSeconds(0);
        when(stepMapper.selectById("step-1")).thenReturn(step(TeamStepStatus.READY));
        when(blackboard.stepExecutionOutcome("step-1"))
            .thenReturn(outcome(TeamStepStatus.COMPLETED, TeamRunStatus.EXECUTING, "真实输出", 0));

        consumer.onExecuteStep(command, channel, 42L, List.of());

        verify(blackboard).executeStepPublic("run-1", "step-1");
        verify(blackboard).markStepExecutionAccepted("run-1", "step-1");
        ArgumentCaptor<TeamRunEvent> eventCaptor = ArgumentCaptor.forClass(TeamRunEvent.class);
        verify(producer).publishRunEvent(eq("run-1"), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(StepCompleted.class);
        assertThat(((StepCompleted) eventCaptor.getValue()).output()).isEqualTo("真实输出");
        verify(channel).basicAck(42L, false);
    }

    @Test
    void runningMysqlStepCanRecoverRedeliveredCommand() throws Exception {
        properties.setStepTimeoutSeconds(0);
        when(stepMapper.selectById("step-1")).thenReturn(step(TeamStepStatus.RUNNING));
        when(blackboard.stepExecutionOutcome("step-1"))
            .thenReturn(outcome(TeamStepStatus.COMPLETED, TeamRunStatus.EXECUTING, "恢复输出", 0));

        consumer.onExecuteStep(command, channel, 42L, List.of());

        verify(blackboard).markStepExecutionAccepted("run-1", "step-1");
        verify(blackboard).executeStepPublic("run-1", "step-1");
        verify(producer).publishRunEvent(eq("run-1"), any(StepCompleted.class));
        verify(dagStore).markMessageProcessed("msg-1");
        verify(channel).basicAck(42L, false);
    }

    @Test
    void retryingOutcomeReleasesSlotAndRequeuesWithoutCompletingDag() throws Exception {
        properties.setStepTimeoutSeconds(0);
        when(stepMapper.selectById("step-1")).thenReturn(step(TeamStepStatus.READY));
        when(blackboard.stepExecutionOutcome("step-1"))
            .thenReturn(outcome(TeamStepStatus.RETRYING, TeamRunStatus.EXECUTING, "", 1));
        when(dagStore.markStepReady("run-1", "step-1")).thenReturn(true);

        consumer.onExecuteStep(command, channel, 42L, List.of());

        verify(dagStore).releaseSlotForRetry("run-1", "step-1", 1);
        verify(producer).publishExecuteStep("run-1", "step-1");
        verify(producer, never()).publishRunEvent(eq("run-1"), any());
        verify(channel).basicAck(42L, false);
    }

    private static TeamStepEntity step(TeamStepStatus status) {
        TeamStepEntity step = new TeamStepEntity();
        step.setStepId("step-1");
        step.setRunId("run-1");
        step.setStatus(status);
        return step;
    }

    private static StepExecutionOutcome outcome(TeamStepStatus stepStatus, TeamRunStatus runStatus,
                                                String output, int retryCount) {
        return new StepExecutionOutcome("step-1", stepStatus, runStatus, output, retryCount, false);
    }
}
