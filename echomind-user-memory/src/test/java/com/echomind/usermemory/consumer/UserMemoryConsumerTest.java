package com.echomind.usermemory.consumer;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MemoryDecision;
import com.echomind.common.model.UserMemoryEvent;
import com.echomind.common.observability.EchoMindTrace;
import com.echomind.usermemory.config.UserMemoryProperties;
import com.echomind.usermemory.service.UserMemoryService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class UserMemoryConsumerTest {

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
            UserMemoryService service = mock(UserMemoryService.class);
            AtomicReference<String> currentTraceId = new AtomicReference<>();
            doAnswer(invocation -> {
                currentTraceId.set(EchoMindTrace.currentTraceId());
                return null;
            }).when(service).ingest(org.mockito.ArgumentMatchers.any());
            UserMemoryProperties properties = new UserMemoryProperties();
            UserMemoryConsumer consumer = new UserMemoryConsumer(service, properties);

            consumer.onEvent(new UserMemoryEvent(
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
