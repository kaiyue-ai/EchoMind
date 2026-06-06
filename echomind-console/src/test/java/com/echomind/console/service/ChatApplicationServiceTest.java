package com.echomind.console.service;

import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.orchestration.AgentOrchestrator.StreamExecution;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatStreamEvent;
import com.echomind.common.model.MessageAttachment;
import com.echomind.common.model.TokenUsage;
import com.echomind.common.observability.EchoMindTrace;
import com.echomind.console.auth.AuthContext;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.budget.ProviderTokenBudgetExceededException;
import com.echomind.console.dto.ChatMessageRequest;
import com.echomind.console.quota.TokenQuotaExceededException;
import com.echomind.memory.MemoryManager;
import com.echomind.skill.storage.ObjectStorageService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 聊天应用服务测试。
 *
 * <p>应用服务只编排请求、执行和清理；配额、敏感数据、告警和用量归治理服务覆盖。</p>
 */
class ChatApplicationServiceTest {

    @Test
    void submitAsyncRegistersRequestOwnerBeforePublishing() {
        ChatRabbitProducer rabbitProducer = mock(ChatRabbitProducer.class);
        SsePushService ssePushService = mock(SsePushService.class);
        ChatApplicationService service = service(mock(AgentOrchestrator.class), rabbitProducer, mock(MemoryManager.class),
            mock(ObjectStorageService.class), ssePushService, passthroughGovernanceService());

        AuthContext.set(new AuthUser("user-a", "alice", true));
        try {
            var response = service.submitAsync(new ChatMessageRequest("default", "hello", "session-a", null, List.of()));
            assertThat(response.traceId()).isNotBlank();
        } finally {
            AuthContext.clear();
        }

        verify(ssePushService).registerRequest(anyString(), eq("user-a"));
        verify(rabbitProducer).publish(argThat((ChatRequest request) ->
            "user-a".equals(request.userId())
                && "session-a".equals(request.sessionId())
                && request.traceId() != null
                && !request.traceId().isBlank()
                && request.traceparent() != null
                && !request.traceparent().isBlank()
        ));
    }

    @Test
    void submitAsyncPublishesGovernedRequestMessage() {
        ChatRabbitProducer rabbitProducer = mock(ChatRabbitProducer.class);
        ChatGovernanceService governanceService = passthroughGovernanceService();
        when(governanceService.inspectRequest(any(), any(), eq("default"), eq("session-a"), eq("hello")))
            .thenReturn(ChatGovernanceService.RequestInspection.continueWith("hello [MASKED]"));
        ChatApplicationService service = service(mock(AgentOrchestrator.class), rabbitProducer, mock(MemoryManager.class),
            mock(ObjectStorageService.class), mock(SsePushService.class), governanceService);

        AuthContext.set(new AuthUser("user-a", "alice", true));
        try {
            service.submitAsync(new ChatMessageRequest("default", "hello", "session-a", null, List.of()));
        } finally {
            AuthContext.clear();
        }

        verify(rabbitProducer).publish(argThat((ChatRequest request) -> "hello [MASKED]".equals(request.message())));
    }

    @Test
    void submitAsyncRejectsQuotaBeforeRegisteringOrPublishing() {
        ChatRabbitProducer rabbitProducer = mock(ChatRabbitProducer.class);
        SsePushService ssePushService = mock(SsePushService.class);
        ChatGovernanceService governanceService = passthroughGovernanceService();
        TokenQuotaExceededException quotaError = new TokenQuotaExceededException("user-a", "daily", 100, 100);
        when(governanceService.inspectRequest(any(), any(), eq("default"), eq("session-a"), eq("hello")))
            .thenThrow(quotaError);
        ChatApplicationService service = service(mock(AgentOrchestrator.class), rabbitProducer, mock(MemoryManager.class),
            mock(ObjectStorageService.class), ssePushService, governanceService);

        AuthContext.set(new AuthUser("user-a", "alice", true));
        try {
            assertThatThrownBy(() -> service.submitAsync(
                new ChatMessageRequest("default", "hello", "session-a", null, List.of())))
                .isSameAs(quotaError);
        } finally {
            AuthContext.clear();
        }

        verifyNoInteractions(ssePushService);
        verifyNoInteractions(rabbitProducer);
    }

    @Test
    void submitAsyncShortCircuitsBlockedRequestWithResultEventAndNoRabbitPublish() {
        ChatRabbitProducer rabbitProducer = mock(ChatRabbitProducer.class);
        SsePushService ssePushService = mock(SsePushService.class);
        ChatGovernanceService governanceService = passthroughGovernanceService();
        when(governanceService.inspectRequest(any(), any(), eq("default"), eq("session-a"),
            eq("call 13800138000")))
            .thenReturn(ChatGovernanceService.RequestInspection.shortCircuit("[PHONE]"));
        ChatApplicationService service = service(mock(AgentOrchestrator.class), rabbitProducer, mock(MemoryManager.class),
            mock(ObjectStorageService.class), ssePushService, governanceService);

        AuthContext.set(new AuthUser("user-a", "alice", true));
        try {
            var response = service.submitAsync(new ChatMessageRequest("default", "call 13800138000",
                "session-a", "mock:model", List.of()));
            assertThat(response.sessionId()).isEqualTo("session-a");
            assertThat(response.traceId()).isNotBlank();
        } finally {
            AuthContext.clear();
        }

        verify(ssePushService).registerRequest(anyString(), eq("user-a"));
        verify(ssePushService).pushEvent(argThat((ChatStreamEvent event) ->
            ChatStreamEvent.TYPE_RESULT.equals(event.type())
                && event.response() != null
                && "[PHONE]".equals(event.response().response())
                && event.response().tokenUsage() == null
                && event.response().skillResults().isEmpty()
                && "OK".equals(event.response().status())
        ));
        verifyNoInteractions(rabbitProducer);
    }
    @Test
    void executeQueuedStreamDoesNotRecordErrorUsageForProviderBudgetBlock() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ChatGovernanceService governanceService = passthroughGovernanceService();
        ProviderTokenBudgetExceededException budgetError =
            new ProviderTokenBudgetExceededException("deepseek", "daily", 1000, 1000);
        when(orchestrator.executeStreamContext(eq("user-a"), eq("default"), eq("session-budget"),
            eq("hello"), eq("deepseek:model"), eq(List.of()), eq("hello"))).thenReturn(Mono.error(budgetError));
        ChatApplicationService service = service(orchestrator, mock(ChatRabbitProducer.class), mock(MemoryManager.class),
            mock(ObjectStorageService.class), mock(SsePushService.class), governanceService);

        var response = service.executeQueuedStream(
            new ChatRequest("req-budget", "user-a", "default", "session-budget", "hello", "deepseek:model",
                "trace-budget", "00-trace-parent", List.of()),
            event -> {}
        );

        assertThat(response.status()).isEqualTo("ERROR");
        assertThat(response.error()).isEqualTo(budgetError.getMessage());
        verify(governanceService, never()).recordStreamUsage(any(), anyString(), any(), any(), anyLong(), anyBoolean(),
            anyString());
        verify(governanceService, never()).emitCallError(any(), any(), anyString());
    }

    @Test
    void executeQueuedStreamReturnsQuotaErrorWithoutCallErrorAlert() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ChatGovernanceService governanceService = passthroughGovernanceService();
        PipelineContext ctx = new PipelineContext();
        ctx.setUserId("user-a");
        ctx.setAgentId("default");
        ctx.setSessionId("session-quota");
        ctx.setModelId("mock:model");
        ctx.setTraceId("trace-quota");
        ctx.setTokenUsage(new TokenUsage(10, 6, 16));
        TokenQuotaExceededException quotaError = new TokenQuotaExceededException("user-a", "daily", 116, 100);
        when(orchestrator.executeStreamContext(eq("user-a"), eq("default"), eq("session-quota"),
            eq("hello"), eq("mock:model"), eq(List.of()), eq("hello")))
            .thenReturn(Mono.just(new StreamExecution(ctx, Flux.just("ok"))));
        doThrow(quotaError).when(governanceService)
            .recordStreamUsage(any(), eq("echomind.chat.stream.consume"), any(), eq(ctx), anyLong(), eq(false), eq(null));
        ChatApplicationService service = service(orchestrator, mock(ChatRabbitProducer.class), mock(MemoryManager.class),
            mock(ObjectStorageService.class), mock(SsePushService.class), governanceService);

        var response = service.executeQueuedStream(
            new ChatRequest("req-quota", "user-a", "default", "session-quota", "hello", "mock:model",
                "trace-quota", "00-trace-parent", List.of()),
            event -> {}
        );

        assertThat(response.status()).isEqualTo("ERROR");
        assertThat(response.error()).isEqualTo(quotaError.getMessage());
        verify(governanceService).recordStreamUsage(any(), eq("echomind.chat.stream.consume"), any(), eq(ctx),
            anyLong(), eq(false), eq(null));
        verify(governanceService, never()).recordStreamUsage(any(), anyString(), any(), any(), anyLong(), eq(true),
            anyString());
        verify(governanceService, never()).emitCallError(any(), any(), anyString());
    }

    @Test
    void executeQueuedStreamPublishesMetaAndTokenEvents() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ChatGovernanceService governanceService = passthroughGovernanceService();
        PipelineContext ctx = new PipelineContext();
        ctx.setUserId("user-a");
        ctx.setAgentId("default");
        ctx.setSessionId("session-a");
        ctx.setModelId("mock:model");
        ctx.setTraceId("trace-a");
        ctx.setTokenUsage(new TokenUsage(1, 2, 3));
        when(orchestrator.executeStreamContext(eq("user-a"), eq("default"), eq("session-a"), eq("hello"),
            eq("mock:model"), eq(List.of()), eq("hello"))).thenReturn(Mono.just(new StreamExecution(ctx, Flux.just("你", "好"))));
        ChatApplicationService service = service(orchestrator, mock(ChatRabbitProducer.class), mock(MemoryManager.class),
            mock(ObjectStorageService.class), mock(SsePushService.class), governanceService);
        List<ChatStreamEvent> events = new ArrayList<>();

        var response = service.executeQueuedStream(
            new ChatRequest("req-1", "user-a", "default", "session-a", "hello", "mock:model", "trace-a",
                "00-trace-parent", List.of()),
            events::add
        );

        assertThat(response.response()).isEqualTo("你好");
        assertThat(events).extracting(ChatStreamEvent::type)
            .containsExactly(ChatStreamEvent.TYPE_META, ChatStreamEvent.TYPE_TOKEN, ChatStreamEvent.TYPE_TOKEN);
        assertThat(events.get(0).traceId()).isEqualTo("trace-a");
        assertThat(events.get(1).token()).isEqualTo("你");
        assertThat(events.get(2).token()).isEqualTo("好");
        verify(governanceService).recordStreamUsage(any(), eq("echomind.chat.stream.consume"), any(), eq(ctx),
            anyLong(), eq(false), eq(null));
        verify(governanceService, never()).inspectRequest(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void executeQueuedStreamKeepsExtractedTraceCurrentThroughStreamConsumption() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String traceparent = "00-" + traceId + "-1111111111111111-01";
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
        EchoMindTrace.setOpenTelemetry(sdk);
        try {
            AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
            ChatGovernanceService governanceService = passthroughGovernanceService();
            AtomicReference<String> streamTraceId = new AtomicReference<>();
            PipelineContext ctx = new PipelineContext();
            ctx.setUserId("user-a");
            ctx.setAgentId("default");
            ctx.setSessionId("session-a");
            ctx.setModelId("mock:model");
            ctx.setTokenUsage(new TokenUsage(1, 2, 3));
            when(orchestrator.executeStreamContext(eq("user-a"), eq("default"), eq("session-a"), eq("hello"),
                eq("mock:model"), eq(List.of()), eq("hello"))).thenAnswer(invocation -> {
                    assertThat(EchoMindTrace.currentTraceId()).isEqualTo(traceId);
                    ctx.setTraceId(EchoMindTrace.currentTraceId());
                    return Mono.just(new StreamExecution(ctx, Flux.defer(() -> {
                        streamTraceId.set(EchoMindTrace.currentTraceId());
                        return Flux.just("你", "好");
                    })));
                });
            ChatApplicationService service = service(orchestrator, mock(ChatRabbitProducer.class),
                mock(MemoryManager.class), mock(ObjectStorageService.class), mock(SsePushService.class),
                governanceService);
            List<ChatStreamEvent> events = new ArrayList<>();

            var response = service.executeQueuedStream(
                new ChatRequest("req-1", "user-a", "default", "session-a", "hello", "mock:model", traceId,
                    traceparent, List.of()),
                events::add
            );

            assertThat(streamTraceId).hasValue(traceId);
            assertThat(events.get(0).traceId()).isEqualTo(traceId);
            assertThat(events)
                .allSatisfy(event -> assertThat(event.traceparent()).startsWith("00-" + traceId + "-"));
            assertThat(response.traceId()).isEqualTo(traceId);
            assertThat(response.traceparent()).startsWith("00-" + traceId + "-");
            verify(governanceService).recordStreamUsage(any(), eq("echomind.chat.stream.consume"), any(), eq(ctx),
                anyLong(), eq(false), eq(null));
        } finally {
            EchoMindTrace.setOpenTelemetry(OpenTelemetry.noop());
            tracerProvider.shutdown();
        }
    }

    @Test
    void executeQueuedStreamPassesRawMessageEvenWhenGovernanceDoesNotChangeIt() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ChatGovernanceService governanceService = passthroughGovernanceService();
        PipelineContext ctx = new PipelineContext();
        ctx.setUserId("user-a");
        ctx.setAgentId("default");
        ctx.setSessionId("session-a");
        ctx.setModelId("mock:model");
        ctx.setTraceId("trace-a");
        when(orchestrator.executeStreamContext(eq("user-a"), eq("default"), eq("session-a"),
            eq("查询明天重庆到临沂的火车票"), eq("mock:model"), eq(List.of()),
            eq("查询明天重庆到临沂的火车票"))).thenReturn(Mono.just(new StreamExecution(ctx, Flux.just("ok"))));
        ChatApplicationService service = service(orchestrator, mock(ChatRabbitProducer.class), mock(MemoryManager.class),
            mock(ObjectStorageService.class), mock(SsePushService.class), governanceService);

        var response = service.executeQueuedStream(
            new ChatRequest("req-1", "user-a", "default", "session-a", "查询明天重庆到临沂的火车票",
                "mock:model", "trace-a", "00-trace-parent", List.of()),
            event -> {}
        );

        assertThat(response.response()).isEqualTo("ok");
        verify(orchestrator).executeStreamContext(eq("user-a"), eq("default"), eq("session-a"),
            eq("查询明天重庆到临沂的火车票"), eq("mock:model"), eq(List.of()),
            eq("查询明天重庆到临沂的火车票"));
    }

    @Test
    void executeQueuedStreamReturnsErrorWhenStreamContextMarksFailure() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ChatGovernanceService governanceService = passthroughGovernanceService();
        PipelineContext ctx = new PipelineContext();
        ctx.setUserId("user-a");
        ctx.setAgentId("default");
        ctx.setSessionId("session-a");
        ctx.setModelId("mock:model");
        ctx.setTraceId("trace-a");
        ctx.markFailed("provider 400");
        when(orchestrator.executeStreamContext(eq("user-a"), eq("default"), eq("session-a"), eq("hello"),
            eq("mock:model"), eq(List.of()), eq("hello"))).thenReturn(Mono.just(new StreamExecution(ctx, Flux.empty())));
        ChatApplicationService service = service(orchestrator, mock(ChatRabbitProducer.class), mock(MemoryManager.class),
            mock(ObjectStorageService.class), mock(SsePushService.class), governanceService);
        List<ChatStreamEvent> events = new ArrayList<>();

        var response = service.executeQueuedStream(
            new ChatRequest("req-1", "user-a", "default", "session-a", "hello", "mock:model", "trace-a",
                "00-trace-parent", List.of()),
            events::add
        );

        assertThat(response.status()).isEqualTo("ERROR");
        assertThat(response.error()).isEqualTo("provider 400");
        assertThat(events).extracting(ChatStreamEvent::type)
            .containsExactly(ChatStreamEvent.TYPE_META);
        verify(governanceService).recordStreamUsage(any(), eq("echomind.chat.stream.consume"), any(), eq(ctx),
            anyLong(), eq(true), eq("provider 400"));
    }

    @Test
    void deleteSessionClearsHistoryAndAttachments() throws IOException {
        MemoryManager memoryManager = mock(MemoryManager.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        MessageAttachment kept = MessageAttachment.image(
            "https://example.com/not-managed.png",
            "https://example.com/not-managed.png",
            "image/png",
            "not-managed.png",
            1L
        );
        MessageAttachment deleted = MessageAttachment.image(
            "oss://my-dev-oss/chat-images/a.png",
            "https://signed.example.com/a.png",
            "image/png",
            "a.png",
            2L
        );
        when(memoryManager.getFullContext("default", "session-1")).thenReturn(List.of(
            new AgentMessage("user", "你好", Instant.now(), null, List.of(kept, deleted)),
            new AgentMessage("assistant", "你好呀", Instant.now(), null, null)
        ));
        when(storageService.supports("https://example.com/not-managed.png")).thenReturn(false);
        when(storageService.supports("oss://my-dev-oss/chat-images/a.png")).thenReturn(true);
        ChatApplicationService service = service(mock(AgentOrchestrator.class), mock(ChatRabbitProducer.class), memoryManager,
            storageService, mock(SsePushService.class), passthroughGovernanceService());

        Map<String, String> result = service.deleteSession("session-1");

        verify(memoryManager).getFullContext("default", "session-1");
        verify(memoryManager).clearSession("default", "session-1");
        verify(storageService, never()).deleteObject("https://example.com/not-managed.png");
        verify(storageService).deleteObject("oss://my-dev-oss/chat-images/a.png");
        assertThat(result).containsEntry("status", "deleted");
        assertThat(result).containsEntry("sessionId", "session-1");
    }

    @Test
    void deleteSessionStillSucceedsWhenAttachmentDeletionFails() throws IOException {
        MemoryManager memoryManager = mock(MemoryManager.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        MessageAttachment deleted = MessageAttachment.image(
            "oss://my-dev-oss/chat-images/a.png",
            "https://signed.example.com/a.png",
            "image/png",
            "a.png",
            2L
        );
        when(memoryManager.getFullContext("default", "session-2")).thenReturn(List.of(
            new AgentMessage("user", "你好", Instant.now(), null, List.of(deleted))
        ));
        when(storageService.supports("oss://my-dev-oss/chat-images/a.png")).thenReturn(true);
        doThrow(new IOException("boom")).when(storageService).deleteObject("oss://my-dev-oss/chat-images/a.png");
        ChatApplicationService service = service(mock(AgentOrchestrator.class), mock(ChatRabbitProducer.class), memoryManager,
            storageService, mock(SsePushService.class), passthroughGovernanceService());

        Map<String, String> result = service.deleteSession("session-2");

        verify(memoryManager).clearSession("default", "session-2");
        verify(storageService).deleteObject("oss://my-dev-oss/chat-images/a.png");
        assertThat(result).containsEntry("status", "deleted");
        assertThat(result).containsEntry("sessionId", "session-2");
    }

    @Test
    void blankSessionIdIsRejectedBeforeAnySideEffects() {
        ChatApplicationService service = service(mock(AgentOrchestrator.class), mock(ChatRabbitProducer.class),
            mock(MemoryManager.class), mock(ObjectStorageService.class), mock(SsePushService.class),
            passthroughGovernanceService());

        assertThatThrownBy(() -> service.deleteSession(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sessionId不能为空");
    }

    @Test
    void deleteSessionSkipsAttachmentLookupWhenHistoryIsEmpty() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        when(memoryManager.getFullContext(anyString(), anyString())).thenReturn(List.of());
        ChatApplicationService service = service(mock(AgentOrchestrator.class), mock(ChatRabbitProducer.class), memoryManager,
            storageService, mock(SsePushService.class), passthroughGovernanceService());

        Map<String, String> result = service.deleteSession("session-3");

        verify(memoryManager).clearSession("default", "session-3");
        verifyNoInteractions(storageService);
        assertThat(result).containsEntry("status", "deleted");
    }

    private ChatApplicationService service(AgentOrchestrator orchestrator, ChatRabbitProducer rabbitProducer,
                                           MemoryManager memoryManager, ObjectStorageService storageService,
                                           SsePushService ssePushService, ChatGovernanceService governanceService) {
        AgentChatExecutor agentChatExecutor = new AgentChatExecutor(orchestrator);
        QueuedChatStreamExecutor queuedChatStreamExecutor =
            new QueuedChatStreamExecutor(agentChatExecutor, governanceService);
        ChatSessionCleanupService sessionCleanupService =
            new ChatSessionCleanupService(memoryManager, storageService);
        return new ChatApplicationService(rabbitProducer, ssePushService, governanceService,
            queuedChatStreamExecutor, sessionCleanupService);
    }

    private ChatGovernanceService passthroughGovernanceService() {
        ChatGovernanceService service = mock(ChatGovernanceService.class);
        when(service.inspectRequest(any(), any(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> ChatGovernanceService.RequestInspection.continueWith(invocation.getArgument(4)));
        when(service.inspectResponseText(any(), anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(4));
        when(service.emitCallErrorIfFailed(any(), any())).thenReturn(false);
        return service;
    }
}
