package com.echomind.agent.team.runtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.echomind.agent.team.messaging.StepCompleted;
import com.echomind.common.exception.ModelInvocationRejectedException;
import com.rabbitmq.client.Channel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeamRunEventConsumerTest {

    private final TeamDagCoordinator coordinator = mock(TeamDagCoordinator.class);
    private final TeamRedisDagStore dagStore = mock(TeamRedisDagStore.class);
    private final TeamRunEventConsumer consumer = new TeamRunEventConsumer(coordinator, dagStore);
    private final Channel channel = mock(Channel.class);
    private final StepCompleted event =
        new StepCompleted("msg-1", "run-1", "step-1", Instant.now(), 0, "output");

    @Test
    void transientRunEventFailureRequeuesWithRedisRetryCounter() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
            .when(coordinator)
            .handle("run-1", event);
        when(dagStore.incrementMessageRetry("msg-1")).thenReturn(1);

        consumer.onRunEvent(event, channel, 42L, List.of());

        verify(channel).basicNack(42L, false, true);
        verify(dagStore, never()).markMessageProcessed("msg-1");
    }

    @Test
    void exhaustedRunEventFailureDeadLettersInsteadOfHotRequeueing() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
            .when(coordinator)
            .handle("run-1", event);
        when(dagStore.incrementMessageRetry("msg-1")).thenReturn(6);

        consumer.onRunEvent(event, channel, 42L, List.of());

        verify(channel).basicNack(42L, false, false);
        verify(coordinator).failRun("run-1", "Run event retry exhausted: boom");
        verify(dagStore, never()).markMessageProcessed("msg-1");
    }

    @Test
    void deterministicGovernanceFailureMarksRunFailedWithoutHotRequeueing() throws Exception {
        org.mockito.Mockito.doThrow(new TeamUsageQuotaExceededException("quota exceeded", null))
            .when(coordinator)
            .handle("run-1", event);

        consumer.onRunEvent(event, channel, 42L, List.of());

        verify(channel).basicAck(42L, false);
        verify(coordinator).failRun("run-1", "Run event failed: quota exceeded");
        verify(dagStore, never()).incrementMessageRetry("msg-1");
        verify(dagStore, never()).markMessageProcessed("msg-1");
    }

    @Test
    void modelPreflightRejectionMarksRunFailedWithoutHotRequeueing() throws Exception {
        org.mockito.Mockito.doThrow(new ModelInvocationRejectedException("provider budget exceeded"))
            .when(coordinator)
            .handle("run-1", event);

        consumer.onRunEvent(event, channel, 42L, List.of());

        verify(channel).basicAck(42L, false);
        verify(coordinator).failRun("run-1", "Run event failed: provider budget exceeded");
        verify(dagStore, never()).incrementMessageRetry("msg-1");
    }
}
