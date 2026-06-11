package com.echomind.console.service;

import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatResponse;
import com.echomind.common.model.ChatStreamEvent;
import com.echomind.common.model.ErrorDetail;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatRabbitConsumerTest {

    @Test
    void pushesStreamEventsAndTerminalResultDirectlyToSseService() {
        ChatApplicationService chatService = mock(ChatApplicationService.class);
        SsePushService ssePushService = mock(SsePushService.class);
        ChatRabbitConsumer consumer = new ChatRabbitConsumer(chatService, ssePushService);
        ChatRequest request = new ChatRequest(
            "req-1", "user-a", "default", "session-a", "hello", "mock:model",
            "trace-a", "00-trace-a", List.of()
        );
        when(chatService.executeQueuedStream(eq(request), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatStreamEvent> events = invocation.getArgument(1);
            events.accept(ChatStreamEvent.meta("req-1", "session-a", "trace-a"));
            events.accept(ChatStreamEvent.token("req-1", "你"));
            events.accept(ChatStreamEvent.toolStart("req-1", "calculator"));
            events.accept(ChatStreamEvent.toolEnd("req-1", "calculator", 12));
            return ChatResponse.success(
                "req-1", "session-a", "default", "mock:model", "你好", List.of(), "trace-a",
                null, "00-trace-a"
            );
        });

        consumer.onChatRequest(request);

        verify(ssePushService).pushEvent(ChatStreamEvent.meta("req-1", "session-a", "trace-a"));
        verify(ssePushService).pushEvent(ChatStreamEvent.token("req-1", "你"));
        verify(ssePushService).pushEvent(ChatStreamEvent.toolStart("req-1", "calculator"));
        verify(ssePushService).pushEvent(ChatStreamEvent.toolEnd("req-1", "calculator", 12));
        verify(ssePushService).pushEvent(anyTerminalEvent(ChatStreamEvent.TYPE_RESULT));
    }

    @Test
    void pushesFailureTerminalEventDirectlyToSseService() {
        ChatApplicationService chatService = mock(ChatApplicationService.class);
        SsePushService ssePushService = mock(SsePushService.class);
        ChatRabbitConsumer consumer = new ChatRabbitConsumer(chatService, ssePushService);
        ChatRequest request = new ChatRequest(
            "req-2", "user-a", "default", "session-a", "hello", "mock:model",
            "trace-a", "00-trace-a", List.of()
        );
        when(chatService.executeQueuedStream(eq(request), any()))
            .thenReturn(ChatResponse.error("req-2", "provider down", "trace-a", "00-trace-a"));

        consumer.onChatRequest(request);

        verify(ssePushService).pushEvent(anyTerminalEvent(ChatStreamEvent.TYPE_FAILURE));
    }

    @Test
    void pushesFailureTerminalEventWithErrorDetail() {
        ChatApplicationService chatService = mock(ChatApplicationService.class);
        SsePushService ssePushService = mock(SsePushService.class);
        ChatRabbitConsumer consumer = new ChatRabbitConsumer(chatService, ssePushService);
        ChatRequest request = new ChatRequest(
            "req-3", "user-a", "default", "session-a", "hello", "mock:model",
            "trace-a", "00-trace-a", List.of()
        );
        ErrorDetail detail = new ErrorDetail("USER_TOKEN_QUOTA_EXCEEDED", "Token 配额已超限",
            "FINAL_PREFLIGHT", "daily", 100L, 100L, null, 1L, 0L, null);
        when(chatService.executeQueuedStream(eq(request), any()))
            .thenReturn(ChatResponse.error("req-3", "Token 配额已超限", detail, "trace-a", "00-trace-a"));

        consumer.onChatRequest(request);

        verify(ssePushService).pushEvent(org.mockito.ArgumentMatchers.argThat(event ->
            event != null
                && ChatStreamEvent.TYPE_FAILURE.equals(event.type())
                && detail.equals(event.errorDetail())
                && "Token 配额已超限".equals(event.error())
        ));
    }

    private ChatStreamEvent anyTerminalEvent(String type) {
        return org.mockito.ArgumentMatchers.argThat(event ->
            event != null
                && type.equals(event.type())
                && event.terminal()
                && "00-trace-a".equals(event.traceparent())
                && "trace-a".equals(event.traceId())
        );
    }
}
