package com.echomind.console.service;

import com.echomind.agent.usermemory.UserMemoryPersistPublisher;
import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.ChatMemoryPersistEvent;
import com.echomind.common.model.MemoryDecision;
import com.echomind.common.observability.EchoMindTrace;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import com.echomind.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChatMemoryPersistConsumerTest {

    @Test
    void persistsMessagesInOrderThenPublishesUserMemoryEvent() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        UserMemoryPersistPublisher publisher = mock(UserMemoryPersistPublisher.class);
        ChatMemoryPersistConsumer consumer = new ChatMemoryPersistConsumer(memoryManager, publisher);
        List<AgentMessage> messages = List.of(
            AgentMessage.user("你好"),
            AgentMessage.assistant("你好呀")
        );

        consumer.onPersistEvent(new ChatMemoryPersistEvent("default", "session-1", "default", messages, MemoryDecision.FALLBACK));

        var ordered = inOrder(memoryManager, publisher);
        ordered.verify(memoryManager).addMessage("default", "session-1", "default", messages.get(0));
        ordered.verify(memoryManager).addMessage("default", "session-1", "default", messages.get(1));
        ordered.verify(publisher).publish("default", "session-1", "default", messages, MemoryDecision.FALLBACK);
    }

    @Test
    void persistsMessagesButSkipsUserMemoryWhenDecisionIsFalse() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        UserMemoryPersistPublisher publisher = mock(UserMemoryPersistPublisher.class);
        ChatMemoryPersistConsumer consumer = new ChatMemoryPersistConsumer(memoryManager, publisher);
        List<AgentMessage> messages = List.of(AgentMessage.user("普通闲聊"));

        consumer.onPersistEvent(new ChatMemoryPersistEvent("default", "session-1", "default", messages, MemoryDecision.NONE));

        verify(memoryManager).addMessage("default", "session-1", "default", messages.get(0));
        verify(publisher, never()).publish(any(), any(), any(), any());
        verify(publisher, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void ignoresInvalidEvent() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        UserMemoryPersistPublisher publisher = mock(UserMemoryPersistPublisher.class);
        ChatMemoryPersistConsumer consumer = new ChatMemoryPersistConsumer(memoryManager, publisher);

        consumer.onPersistEvent(new ChatMemoryPersistEvent(" ", "default", List.of(AgentMessage.user("x"))));

        verify(memoryManager, never()).addMessage(any(), any(), any(), any());
        verify(publisher, never()).publish(any(), any(), any(), any());
        verify(publisher, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void restoresTraceContextBeforePublishingUserMemoryEvent() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String traceparent = "00-" + traceId + "-1111111111111111-01";
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
        EchoMindTrace.setOpenTelemetry(sdk);
        try {
            MemoryManager memoryManager = mock(MemoryManager.class);
            UserMemoryPersistPublisher publisher = mock(UserMemoryPersistPublisher.class);
            AtomicReference<String> currentTraceId = new AtomicReference<>();
            doAnswer(invocation -> {
                currentTraceId.set(EchoMindTrace.currentTraceId());
                return null;
            }).when(publisher).publish(eq("user-a"), eq("session-1"), eq("default"), any(), eq(MemoryDecision.FALLBACK));
            ChatMemoryPersistConsumer consumer = new ChatMemoryPersistConsumer(memoryManager, publisher);

            consumer.onPersistEvent(new ChatMemoryPersistEvent(
                "user-a",
                "session-1",
                "default",
                List.of(AgentMessage.user("你好")),
                MemoryDecision.FALLBACK,
                traceId,
                traceparent
            ));

            assertThat(currentTraceId).hasValue(traceId);
        } finally {
            EchoMindTrace.setOpenTelemetry(OpenTelemetry.noop());
            tracerProvider.shutdown();
        }
    }

    @Test
    void restoresTraceContextFromTraceIdWhenTraceparentIsMissing() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
        EchoMindTrace.setOpenTelemetry(sdk);
        try {
            MemoryManager memoryManager = mock(MemoryManager.class);
            UserMemoryPersistPublisher publisher = mock(UserMemoryPersistPublisher.class);
            AtomicReference<String> currentTraceId = new AtomicReference<>();
            doAnswer(invocation -> {
                currentTraceId.set(EchoMindTrace.currentTraceId());
                return null;
            }).when(publisher).publish(eq("user-a"), eq("session-1"), eq("default"), any(), eq(MemoryDecision.FALLBACK));
            ChatMemoryPersistConsumer consumer = new ChatMemoryPersistConsumer(memoryManager, publisher);

            consumer.onPersistEvent(new ChatMemoryPersistEvent(
                "user-a",
                "session-1",
                "default",
                List.of(AgentMessage.user("你好")),
                MemoryDecision.FALLBACK,
                traceId,
                null
            ));

            assertThat(currentTraceId).hasValue(traceId);
        } finally {
            EchoMindTrace.setOpenTelemetry(OpenTelemetry.noop());
            tracerProvider.shutdown();
        }
    }
}
