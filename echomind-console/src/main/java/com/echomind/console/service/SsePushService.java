package com.echomind.console.service;

import com.echomind.common.model.ChatStreamEvent;
import com.echomind.common.observability.EchoMindTrace;
import com.echomind.console.config.RabbitMQConfig;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class SsePushService {

    private static final long SSE_TIMEOUT_MS = 300_000;
    private static final int MAX_BUFFERED_EVENTS = 512;

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> requestOwners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<ChatStreamEvent>> bufferedEvents = new ConcurrentHashMap<>();

    public void registerRequest(String requestId, String userId) {
        requestOwners.put(requestId, normalizeUser(userId));
    }

    public void discardRequest(String requestId) {
        cleanup(requestId);
    }

    public SseEmitter createEmitter(String requestId, String userId) {
        assertOwner(requestId, userId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(requestId, emitter);
        replayBufferedEvents(requestId, emitter);
        emitter.onCompletion(() -> cleanupEmitter(requestId));
        emitter.onTimeout(() -> {
            cleanupEmitter(requestId);
            log.info("SSE emitter timed out for request {}", requestId);
        });
        emitter.onError(e -> {
            cleanupEmitter(requestId);
            log.warn("SSE emitter error for request {}: {}", requestId, e.getMessage());
        });
        return emitter;
    }

    @RabbitListener(
        queues = RabbitMQConfig.QUEUE_CHAT_STREAM_EVENTS,
        containerFactory = RabbitMQConfig.CHAT_STREAM_EVENT_LISTENER_FACTORY
    )
    public void onChatStreamEvent(ChatStreamEvent event) {
        pushEvent(event);
    }

    public void pushEvent(ChatStreamEvent event) {
        if (event == null || event.requestId() == null || event.requestId().isBlank()) {
            return;
        }
        if (ChatStreamEvent.TYPE_TOKEN.equals(event.type())) {
            try (Scope ignored = extractEventContext(event).makeCurrent()) {
                handleEvent(event);
            }
            return;
        }
        Span span = EchoMindTrace.startSpan("echomind.chat.sse.push", extractEventContext(event));
        tagEvent(span, event);
        try (Scope ignored = span.makeCurrent()) {
            handleEvent(event);
        } catch (RuntimeException e) {
            EchoMindTrace.recordException(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void handleEvent(ChatStreamEvent event) {
        remember(event);
        SseEmitter emitter = emitters.get(event.requestId());
        if (emitter != null) {
            send(event.requestId(), emitter, event);
        }
        if (event.terminal()) {
            complete(event.requestId(), emitter);
        }
    }

    private void assertOwner(String requestId, String userId) {
        String owner = requestOwners.get(requestId);
        if (owner == null || !owner.equals(normalizeUser(userId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "chat request not found");
        }
    }

    private void replayBufferedEvents(String requestId, SseEmitter emitter) {
        Deque<ChatStreamEvent> events = bufferedEvents.get(requestId);
        if (events == null || events.isEmpty()) {
            return;
        }
        List<ChatStreamEvent> snapshot;
        synchronized (events) {
            snapshot = new ArrayList<>(events);
        }
        for (ChatStreamEvent event : snapshot) {
            send(requestId, emitter, event);
        }
        if (!snapshot.isEmpty() && snapshot.get(snapshot.size() - 1).terminal()) {
            complete(requestId, emitter);
        }
    }

    private void remember(ChatStreamEvent event) {
        Deque<ChatStreamEvent> events = bufferedEvents.computeIfAbsent(event.requestId(), ignored -> new ArrayDeque<>());
        synchronized (events) {
            if (events.size() >= MAX_BUFFERED_EVENTS) {
                events.removeFirst();
            }
            events.addLast(event);
        }
    }

    private void send(String requestId, SseEmitter emitter, ChatStreamEvent event) {
        try {
            switch (event.type()) {
                case ChatStreamEvent.TYPE_META -> emitter.send(SseEmitter.event()
                    .name("meta")
                    .data(Map.of(
                        "sessionId", safe(event.sessionId()),
                        "traceId", safe(event.traceId())
                    )));
                case ChatStreamEvent.TYPE_TOKEN -> emitter.send(SseEmitter.event()
                    .name("token")
                    .data(Map.of("token", safe(event.token()))));
                case ChatStreamEvent.TYPE_TOOL_START -> emitter.send(SseEmitter.event()
                    .name("tool_start")
                    .data(Map.of("toolName", safe(event.toolName()))));
                case ChatStreamEvent.TYPE_TOOL_END -> emitter.send(SseEmitter.event()
                    .name("tool_end")
                    .data(Map.of(
                        "toolName", safe(event.toolName()),
                        "durationMs", event.durationMs()
                    )));
                case ChatStreamEvent.TYPE_RESULT -> emitter.send(SseEmitter.event()
                    .name("result")
                    .data(event.response()));
                case ChatStreamEvent.TYPE_FAILURE -> emitter.send(SseEmitter.event()
                    .name("failure")
                    .data(Map.of("error", safe(event.error()), "traceId", safe(event.traceId()))));
                default -> log.debug("Ignoring unknown chat stream event type={} request={}", event.type(), requestId);
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
            cleanupEmitter(requestId);
            log.warn("SSE send failed for {}: {}", requestId, e.getMessage());
        }
    }

    private void complete(String requestId, SseEmitter emitter) {
        if (emitter != null) {
            emitter.complete();
            cleanup(requestId);
        } else {
            CompletableFuture.delayedExecutor(SSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    if (!emitters.containsKey(requestId)) {
                        cleanup(requestId);
                    }
                });
        }
    }

    private void cleanup(String requestId) {
        cleanupEmitter(requestId);
        requestOwners.remove(requestId);
        bufferedEvents.remove(requestId);
    }

    private void cleanupEmitter(String requestId) {
        emitters.remove(requestId);
    }

    private String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private io.opentelemetry.context.Context extractEventContext(ChatStreamEvent event) {
        return EchoMindTrace.extractContext(event.traceparent(), event.traceId());
    }

    private void tagEvent(Span span, ChatStreamEvent event) {
        span.setAttribute("echomind.request_id", safe(event.requestId()));
        span.setAttribute("echomind.event_type", safe(event.type()));
        span.setAttribute("echomind.session_id", safe(event.sessionId()));
        span.setAttribute("echomind.trace.parent_id", safe(event.traceId()));
    }
}
