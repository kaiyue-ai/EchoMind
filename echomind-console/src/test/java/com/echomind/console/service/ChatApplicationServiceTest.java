package com.echomind.console.service;

import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.orchestration.AgentOrchestrator.StreamExecution;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatStreamEvent;
import com.echomind.common.model.MessageAttachment;
import com.echomind.common.model.TokenUsage;
import com.echomind.console.auth.AuthContext;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.dto.ChatMessageRequest;
import com.echomind.memory.MemoryManager;
import com.echomind.skill.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    void executeSyncUsesGovernedRequestAndResponseText() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ChatGovernanceService governanceService = passthroughGovernanceService();
        PipelineContext ctx = new PipelineContext();
        ctx.setUserId("user-a");
        ctx.setAgentId("default");
        ctx.setSessionId("session-a");
        ctx.setModelId("mock:model");
        ctx.setTraceId("trace-a");
        ctx.setFinalResponse("reply alice@example.com");
        when(governanceService.inspectRequest(any(), any(), eq("default"), eq("session-a"), eq("call 13800138000")))
            .thenReturn("call [PHONE]");
        doAnswer(invocation -> {
            PipelineContext governed = invocation.getArgument(1);
            governed.setFinalResponse("reply [EMAIL]");
            return null;
        }).when(governanceService).inspectResponse(any(), eq(ctx));
        when(orchestrator.execute(eq("user-a"), eq("default"), eq("session-a"), eq("call [PHONE]"),
            eq("mock:model"), any(), eq("call 13800138000"))).thenReturn(ctx);
        ChatApplicationService service = service(orchestrator, mock(ChatRabbitProducer.class), mock(MemoryManager.class),
            mock(ObjectStorageService.class), mock(SsePushService.class), governanceService);

        AuthContext.set(new AuthUser("user-a", "alice", true));
        try {
            var response = service.executeSync(new ChatMessageRequest("default", "call 13800138000",
                "session-a", "mock:model", List.of()));
            assertThat(response.response()).isEqualTo("reply [EMAIL]");
        } finally {
            AuthContext.clear();
        }

        verify(orchestrator).execute(eq("user-a"), eq("default"), eq("session-a"), eq("call [PHONE]"),
            eq("mock:model"), any(), eq("call 13800138000"));
    }

    @Test
    void executeSyncDoesNotEmitDuplicateCallErrorWhenPipelineFailureWasAlreadyReported() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ChatGovernanceService governanceService = passthroughGovernanceService();
        PipelineContext ctx = new PipelineContext();
        ctx.setUserId("user-a");
        ctx.setAgentId("default");
        ctx.setSessionId("session-error");
        ctx.setModelId("mock:model");
        ctx.setTraceId("trace-error");
        ctx.setFinalResponse("[Error] LLM call failed: boom");
        when(orchestrator.execute(eq("user-a"), eq("default"), eq("session-error"), eq("hello"),
            eq("mock:model"), any(), eq("hello"))).thenReturn(ctx);
        when(governanceService.emitCallErrorIfFailed(any(), eq(ctx))).thenReturn(true);
        doThrow(new IllegalStateException("模型未返回原生 token usage"))
            .when(governanceService).recordSuccessAndWarnings(any(), anyString(), any(), eq(ctx), anyLong());
        ChatApplicationService service = service(orchestrator, mock(ChatRabbitProducer.class), mock(MemoryManager.class),
            mock(ObjectStorageService.class), mock(SsePushService.class), governanceService);

        AuthUser user = new AuthUser("user-a", "alice", true);
        AuthContext.set(user);
        try {
            assertThatThrownBy(() -> service.executeSync(new ChatMessageRequest("default", "hello",
                "session-error", "mock:model", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型未返回原生 token usage");
        } finally {
            AuthContext.clear();
        }

        verify(governanceService).emitCallErrorIfFailed(user, ctx);
        verify(governanceService, never()).emitCallError(eq(user), eq(ctx), anyString());
    }

    @Test
    void executeSyncEmitsCallErrorWhenGovernanceFailsBeforePipelineErrorAlert() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ChatGovernanceService governanceService = passthroughGovernanceService();
        PipelineContext ctx = new PipelineContext();
        ctx.setUserId("user-a");
        ctx.setAgentId("default");
        ctx.setSessionId("session-error");
        ctx.setModelId("mock:model");
        ctx.setTraceId("trace-error");
        ctx.setFinalResponse("partial");
        when(orchestrator.execute(eq("user-a"), eq("default"), eq("session-error"), eq("hello"),
            eq("mock:model"), any(), eq("hello"))).thenReturn(ctx);
        doThrow(new IllegalStateException("模型未返回原生 token usage"))
            .when(governanceService).recordSuccessAndWarnings(any(), anyString(), any(), eq(ctx), anyLong());
        ChatApplicationService service = service(orchestrator, mock(ChatRabbitProducer.class), mock(MemoryManager.class),
            mock(ObjectStorageService.class), mock(SsePushService.class), governanceService);

        AuthUser user = new AuthUser("user-a", "alice", true);
        AuthContext.set(user);
        try {
            assertThatThrownBy(() -> service.executeSync(new ChatMessageRequest("default", "hello",
                "session-error", "mock:model", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型未返回原生 token usage");
        } finally {
            AuthContext.clear();
        }

        verify(governanceService).emitCallError(user, ctx, "模型未返回原生 token usage");
    }

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
            .thenReturn("hello [MASKED]");
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
        when(memoryManager.getFullContext(anyString())).thenReturn(List.of());
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
        return new ChatApplicationService(agentChatExecutor, rabbitProducer, ssePushService, governanceService,
            queuedChatStreamExecutor, sessionCleanupService);
    }

    private ChatGovernanceService passthroughGovernanceService() {
        ChatGovernanceService service = mock(ChatGovernanceService.class);
        when(service.inspectRequest(any(), any(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(4));
        when(service.inspectResponseText(any(), anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(4));
        when(service.emitCallErrorIfFailed(any(), any())).thenReturn(false);
        return service;
    }
}
