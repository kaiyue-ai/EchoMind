package com.echomind.agent.team.runtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.echomind.agent.team.messaging.TeamControlAction;
import com.echomind.agent.team.messaging.TeamControlCommand;
import com.echomind.common.exception.ModelInvocationRejectedException;
import com.rabbitmq.client.Channel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeamControlConsumerTest {

    private final TeamDagCoordinator coordinator = mock(TeamDagCoordinator.class);
    private final TeamRedisDagStore dagStore = mock(TeamRedisDagStore.class);
    private final TeamControlConsumer consumer = new TeamControlConsumer(coordinator, dagStore);
    private final Channel channel = mock(Channel.class);

    @Test
    void planCommandRunsPlanningAndAcks() throws Exception {
        TeamControlCommand cmd = command(TeamControlAction.PLAN_AND_REVIEW);

        consumer.onControl(cmd, channel, 42L, List.of());

        verify(coordinator).startRunPlan("run-1");
        verify(dagStore).markMessageProcessed("msg-1");
        verify(channel).basicAck(42L, false);
    }

    @Test
    void dagCompleteCommandRunsMergeAndAcks() throws Exception {
        TeamControlCommand cmd = command(TeamControlAction.DAG_COMPLETE);

        consumer.onControl(cmd, channel, 42L, List.of());

        verify(coordinator).completeDag("run-1");
        verify(dagStore).markMessageProcessed("msg-1");
        verify(channel).basicAck(42L, false);
    }

    @Test
    void deterministicFailureMarksRunFailedWithoutHotRequeueing() throws Exception {
        TeamControlCommand cmd = command(TeamControlAction.PLAN_AND_REVIEW);
        org.mockito.Mockito.doThrow(new ModelInvocationRejectedException("provider blocked"))
            .when(coordinator)
            .startRunPlan("run-1");

        consumer.onControl(cmd, channel, 42L, List.of());

        verify(coordinator).failRun("run-1", "Team control failed: provider blocked");
        verify(channel).basicAck(42L, false);
        verify(dagStore, never()).incrementMessageRetry("msg-1");
    }

    @Test
    void exhaustedFailureDeadLettersAndFailsRun() throws Exception {
        TeamControlCommand cmd = command(TeamControlAction.PLAN_AND_REVIEW);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
            .when(coordinator)
            .startRunPlan("run-1");
        when(dagStore.incrementMessageRetry("msg-1")).thenReturn(6);

        consumer.onControl(cmd, channel, 42L, List.of());

        verify(coordinator).failRun("run-1", "Team control retry exhausted: boom");
        verify(channel).basicNack(42L, false, false);
    }

    private TeamControlCommand command(TeamControlAction action) {
        return new TeamControlCommand("msg-1", "run-1", null, Instant.now(), 0, action);
    }
}
