package com.echomind.usermemory.consumer;

import com.echomind.common.model.UserMemoryEvent;
import com.echomind.common.observability.EchoMindTrace;
import com.echomind.usermemory.config.UserMemoryProperties;
import com.echomind.usermemory.service.UserMemoryService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserMemoryConsumer {

    private final UserMemoryService userMemoryService;
    private final UserMemoryProperties properties;

    @RabbitListener(queues = "#{@userMemoryQueue.name}")
    public void onEvent(UserMemoryEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        if (event == null || event.sessionId() == null || event.sessionId().isBlank()
            || event.messages() == null || event.messages().isEmpty()) {
            log.warn("Ignoring invalid user memory event");
            return;
        }
        Span span = EchoMindTrace.startSpan("echomind.user-memory.consume",
            EchoMindTrace.extractContext(event.traceparent(), event.traceId()));
        tagEvent(span, event);
        try {
            try (Scope ignored = span.makeCurrent()) {
                userMemoryService.ingest(event);
            }
        } catch (Exception e) {
            EchoMindTrace.recordException(span, e);
            log.warn("Failed to process user memory event sessionId={}: {}", event.sessionId(), e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    private void tagEvent(Span span, UserMemoryEvent event) {
        if (event == null) {
            return;
        }
        span.setAttribute("echomind.trace.parent_id", safe(event.traceId()));
        span.setAttribute("echomind.user_id", safe(event.userId()));
        span.setAttribute("echomind.agent_id", safe(event.agentId()));
        span.setAttribute("echomind.session_id", safe(event.sessionId()));
        span.setAttribute("echomind.message_count", event.messages() == null ? 0 : event.messages().size());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
