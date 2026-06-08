package com.echomind.console.deadletter;

import com.echomind.agent.messaging.RabbitReliableMessaging;
import com.echomind.common.messaging.ChatMemoryShardSupport;
import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.ChatMemoryPersistEvent;
import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatStreamEvent;
import com.echomind.common.model.MemoryDecision;
import com.echomind.common.model.UserMemoryEvent;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.config.RabbitMQConfig;
import com.echomind.console.service.ChatGovernanceService;
import com.echomind.console.service.SsePushService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RabbitDeadLetterServiceTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void chatRequestDeadLetterIsArchivedCompensatedAndPushedAsFailure() throws Exception {
        RabbitDeadLetterMapper deadLetterMapper = mapperWithArchiveStorage();
        ChatGovernanceService governance = mock(ChatGovernanceService.class);
        SsePushService sse = mock(SsePushService.class);
        RabbitDeadLetterService service = service(deadLetterMapper, governance, sse, mock(RabbitTemplate.class));
        ChatRequest request = new ChatRequest("req-1", "user-a", "default", "session-a", "hello",
            "mock:model", "trace-a", "00-trace-a", List.of(), List.of("reservation-a"),
            List.of("provider-reservation"));

        RabbitDeadLetterEntity archived = service.archiveAndCompensate(
            RabbitReliableMessaging.CHAT_REQUESTS_DLQ,
            message(RabbitReliableMessaging.CHAT_REQUESTS_DLQ, mapper.writeValueAsBytes(request))
        );

        assertThat(archived.getMessageType()).isEqualTo(RabbitDeadLetterService.TYPE_CHAT_REQUEST);
        assertThat(archived.getBusinessKey()).isEqualTo("req-1");
        assertThat(archived.getTraceId()).isEqualTo("trace-a");
        assertThat(archived.getStatus()).isEqualTo(RabbitDeadLetterStatus.COMPENSATED);
        assertThat(archived.getErrorHeadersJson()).contains("x-exception-message").contains("boom");
        verify(governance).releaseReservations(List.of("reservation-a"));
        verify(governance).releaseReservations(List.of("provider-reservation"));
        verify(sse).pushEvent(argThat((ChatStreamEvent event) ->
            ChatStreamEvent.TYPE_FAILURE.equals(event.type())
                && "req-1".equals(event.requestId())
                && event.error().contains("RabbitMQ DLQ")
                && "trace-a".equals(event.traceId())
                && "00-trace-a".equals(event.traceparent())
        ));
        verify(deadLetterMapper).updateStatus(archived.getId(), RabbitDeadLetterStatus.COMPENSATED);
    }

    @Test
    void chatMemoryDeadLetterIsOnlyArchived() throws Exception {
        RabbitDeadLetterMapper deadLetterMapper = mapperWithArchiveStorage();
        ChatGovernanceService governance = mock(ChatGovernanceService.class);
        SsePushService sse = mock(SsePushService.class);
        RabbitDeadLetterService service = service(deadLetterMapper, governance, sse, mock(RabbitTemplate.class));
        ChatMemoryPersistEvent event = new ChatMemoryPersistEvent("user-a", "session-a", "default",
            List.of(AgentMessage.user("hello")), MemoryDecision.FALLBACK, "trace-m", "00-trace-m");

        RabbitDeadLetterEntity archived = service.archiveAndCompensate(
            RabbitReliableMessaging.CHAT_MEMORY_PERSIST_DLQ,
            message(RabbitReliableMessaging.CHAT_MEMORY_PERSIST_DLQ, mapper.writeValueAsBytes(event))
        );

        assertThat(archived.getMessageType()).isEqualTo(RabbitDeadLetterService.TYPE_CHAT_MEMORY);
        assertThat(archived.getBusinessKey()).isEqualTo("session-a");
        assertThat(archived.getTraceId()).isEqualTo("trace-m");
        assertThat(archived.getStatus()).isEqualTo(RabbitDeadLetterStatus.ARCHIVED);
        verifyNoInteractions(governance, sse);
        verify(deadLetterMapper, never()).updateStatus(any(), anyString());
    }

    @Test
    void userMemoryDeadLetterArchivesPayloadAndHeaders() throws Exception {
        RabbitDeadLetterMapper deadLetterMapper = mapperWithArchiveStorage();
        RabbitDeadLetterService service = service(deadLetterMapper, mock(ChatGovernanceService.class),
            mock(SsePushService.class), mock(RabbitTemplate.class));
        UserMemoryEvent event = new UserMemoryEvent("user-a", "session-u", "default",
            List.of(AgentMessage.user("记住我喜欢简洁代码")), MemoryDecision.FALLBACK, "trace-u", "00-trace-u");

        RabbitDeadLetterEntity archived = service.archiveAndCompensate(
            RabbitReliableMessaging.USER_MEMORY_DLQ,
            message(RabbitReliableMessaging.USER_MEMORY_DLQ, mapper.writeValueAsBytes(event))
        );

        assertThat(archived.getMessageType()).isEqualTo(RabbitDeadLetterService.TYPE_USER_MEMORY);
        assertThat(archived.getBusinessKey()).isEqualTo("session-u");
        assertThat(archived.getPayloadJson()).contains("session-u");
        assertThat(archived.getErrorHeadersJson()).contains("x-exception-message").contains("boom");
    }

    @Test
    void replayChatRequestReservesFreshQuotaAndPublishesToRequestQueue() throws Exception {
        RabbitDeadLetterMapper deadLetterMapper = mock(RabbitDeadLetterMapper.class);
        ChatGovernanceService governance = mock(ChatGovernanceService.class);
        SsePushService sse = mock(SsePushService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RabbitDeadLetterService service = service(deadLetterMapper, governance, sse, rabbitTemplate);
        ChatRequest request = new ChatRequest("req-replay", "user-a", "default", "session-a", "hello",
            "mock:model", "trace-a", "00-trace-a", List.of(), List.of("old-reservation"));
        RabbitDeadLetterEntity entity = entity(42L, RabbitDeadLetterService.TYPE_CHAT_REQUEST,
            mapper.writeValueAsString(request));
        when(deadLetterMapper.selectById(42L)).thenReturn(entity);
        when(governance.reserveUserQuota(any(AuthUser.class), eq("req-replay"), anyLong())).thenReturn(List.of("new-reservation"));

        service.replay(42L);

        verify(sse).registerRequest("req-replay", "user-a");
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.QUEUE_CHAT_REQUESTS),
            argThat((ChatRequest replay) -> replay.userReservationIds().equals(List.of("new-reservation"))
                && "req-replay".equals(replay.requestId())),
            any(MessagePostProcessor.class),
            any(CorrelationData.class));
        verify(deadLetterMapper).markReplaySuccess(eq(42L), eq(RabbitDeadLetterStatus.REPLAYED), any(Instant.class));
    }

    @Test
    void replayChatMemoryUsesShardRoutingInsteadOfWritingHistoryDirectly() throws Exception {
        RabbitDeadLetterMapper deadLetterMapper = mock(RabbitDeadLetterMapper.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RabbitDeadLetterService service = service(deadLetterMapper, mock(ChatGovernanceService.class),
            mock(SsePushService.class), rabbitTemplate);
        ChatMemoryPersistEvent event = new ChatMemoryPersistEvent("user-a", "session-shard", "default",
            List.of(new AgentMessage("assistant", "ok", Instant.parse("2026-06-07T00:00:00Z"), null, null)),
            MemoryDecision.FALLBACK, "trace-m", "00-trace-m");
        RabbitDeadLetterEntity entity = entity(43L, RabbitDeadLetterService.TYPE_CHAT_MEMORY,
            mapper.writeValueAsString(event));
        when(deadLetterMapper.selectById(43L)).thenReturn(entity);

        service.replay(43L);

        int shard = ChatMemoryShardSupport.shardIndex("session-shard", 4);
        verify(rabbitTemplate).convertAndSend(eq("chat.memory.exchange"), eq(ChatMemoryShardSupport.routingKey(shard)),
            eq(event), any(MessagePostProcessor.class), any(CorrelationData.class));
        verify(deadLetterMapper).markReplaySuccess(eq(43L), eq(RabbitDeadLetterStatus.REPLAYED), any(Instant.class));
    }

    @Test
    void replayFailureMarksRecordWithoutDeletingOriginalPayload() {
        RabbitDeadLetterMapper deadLetterMapper = mock(RabbitDeadLetterMapper.class);
        RabbitDeadLetterService service = service(deadLetterMapper, mock(ChatGovernanceService.class),
            mock(SsePushService.class), mock(RabbitTemplate.class));
        RabbitDeadLetterEntity entity = entity(44L, RabbitDeadLetterService.TYPE_UNKNOWN, "{\"hello\":\"world\"}");
        when(deadLetterMapper.selectById(44L)).thenReturn(entity);

        assertThatThrownBy(() -> service.replay(44L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported dead letter type");

        verify(deadLetterMapper).markReplayFailure(eq(44L), eq(RabbitDeadLetterStatus.REPLAY_FAILED),
            argThat(error -> error.contains("unsupported dead letter type")));
        verify(deadLetterMapper, never()).deleteById(44L);
    }

    private RabbitDeadLetterService service(RabbitDeadLetterMapper deadLetterMapper,
                                            ChatGovernanceService governance,
                                            SsePushService sse,
                                            RabbitTemplate rabbitTemplate) {
        RabbitDeadLetterService service = new RabbitDeadLetterService(deadLetterMapper, mapper, governance, sse,
            rabbitTemplate);
        ReflectionTestUtils.setField(service, "chatMemoryExchangeName", "chat.memory.exchange");
        ReflectionTestUtils.setField(service, "chatMemoryShardCount", 4);
        ReflectionTestUtils.setField(service, "userMemoryQueueName", "user.memory.queue");
        return service;
    }

    private RabbitDeadLetterMapper mapperWithArchiveStorage() {
        RabbitDeadLetterMapper deadLetterMapper = mock(RabbitDeadLetterMapper.class);
        AtomicReference<RabbitDeadLetterEntity> saved = new AtomicReference<>();
        doAnswer(invocation -> {
            RabbitDeadLetterEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            saved.set(entity);
            return 1;
        }).when(deadLetterMapper).insertIgnore(any(RabbitDeadLetterEntity.class));
        when(deadLetterMapper.selectByMessageHash(anyString())).thenAnswer(invocation -> saved.get());
        return deadLetterMapper;
    }

    private Message message(String queue, byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue(queue);
        properties.setMessageId("message-" + queue);
        properties.setCorrelationId("correlation-" + queue);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader("x-exception-message", "boom");
        properties.setHeader("x-exception-stacktrace", "stack");
        return new Message(body, properties);
    }

    private RabbitDeadLetterEntity entity(long id, String type, String payloadJson) {
        RabbitDeadLetterEntity entity = new RabbitDeadLetterEntity();
        entity.setId(id);
        entity.setMessageHash("hash-" + id);
        entity.setDlqName("dlq-" + id);
        entity.setMessageType(type);
        entity.setPayloadJson(payloadJson);
        entity.setErrorHeadersJson("{}");
        entity.setStatus(RabbitDeadLetterStatus.ARCHIVED);
        entity.setArchivedAt(Instant.now());
        return entity;
    }
}
