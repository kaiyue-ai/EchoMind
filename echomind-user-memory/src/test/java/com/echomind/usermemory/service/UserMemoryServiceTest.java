package com.echomind.usermemory.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.UserMemoryEvent;
import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.usermemory.UserMemoryCategory;
import com.echomind.memory.usermemory.UserMemoryEntry;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import com.echomind.memory.usermemory.UserProfileSnapshot;
import com.echomind.memory.usermemory.UserProfileSnapshotStore;
import com.echomind.usermemory.config.UserMemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryServiceTest {

    @Test
    void appendsToBufferAndSkipsLlmBeforeBatchIsFull() {
        Fixture fixture = fixture();
        UserMemoryService service = fixture.service();

        for (int i = 1; i <= 4; i++) {
            service.process(event("user-a", "session-" + i, "消息-" + i));
        }

        verify(fixture.analyzer, never()).analyze(anyString(), anyString(), any(), any());
        assertThat(fixture.buffer).hasSize(4);
    }

    @Test
    void processesFiveTurnsAndWritesFactsAndProfileSnapshot() {
        Fixture fixture = fixture();
        when(fixture.embeddingClient.embed(anyString())).thenReturn(Optional.of(new double[] {0.1, 0.2}));
        when(fixture.vectorStore.search(eq("user:user-a"), any(), eq(12), anyDouble())).thenReturn(List.of(
            new UserMemoryHit("old-fact", UserMemoryCategory.PREFERENCE, "用户喜欢英文注释", "", 0.8, 0.9)
        ));
        when(fixture.snapshotStore.get("user-a")).thenReturn(Optional.of(
            new UserProfileSnapshot("user-a", "旧画像", 1, null)
        ));
        when(fixture.analyzer.analyze(eq("user:user-a"), eq("旧画像"), any(), any())).thenReturn(
            new UserMemoryBatchResult(
                List.of(new UserMemoryBatchResult.FactToAdd("用户希望注释使用中文", UserMemoryCategory.PREFERENCE, "用户明确要求", 0.9)),
                List.of(new UserMemoryBatchResult.FactToUpdate("old-fact", "用户现在希望注释使用中文", UserMemoryCategory.PREFERENCE, "用户明确要求", 0.9)),
                List.of(new UserMemoryBatchResult.FactToDelete("old-fact", "旧偏好被覆盖")),
                "用户维护 EchoMind，偏好中文注释。"
            )
        );
        UserMemoryService service = fixture.service();

        for (int i = 1; i <= 5; i++) {
            service.process(event("user-a", "session-" + i, "消息-" + i));
        }

        verify(fixture.analyzer).analyze(eq("user:user-a"), eq("旧画像"), any(), any());
        verify(fixture.vectorStore).deleteEntry("user:user-a", "old-fact");
        verify(fixture.vectorStore, org.mockito.Mockito.times(2)).save(any(UserMemoryEntry.class));
        verify(fixture.snapshotStore).save(org.mockito.ArgumentMatchers.argThat(snapshot ->
            snapshot.userId().equals("user-a") && snapshot.content().contains("中文注释")
        ));
        assertThat(fixture.buffer).isEmpty();
    }

    @Test
    void keepsBufferWhenLlmAnalysisFails() {
        Fixture fixture = fixture();
        when(fixture.embeddingClient.embed(anyString())).thenReturn(Optional.of(new double[] {0.1, 0.2}));
        when(fixture.analyzer.analyze(eq("user:user-a"), eq(""), any(), any())).thenReturn(
            UserMemoryBatchResult.failed("")
        );
        UserMemoryService service = fixture.service();

        for (int i = 1; i <= 5; i++) {
            service.process(event("user-a", "session-" + i, "消息-" + i));
        }

        verify(fixture.vectorStore, never()).save(any(UserMemoryEntry.class));
        verify(fixture.snapshotStore, never()).save(any(UserProfileSnapshot.class));
        assertThat(fixture.buffer).hasSize(5);
    }

    private UserMemoryEvent event(String userId, String sessionId, String content) {
        return new UserMemoryEvent(userId, sessionId, "agent-1", List.of(
            AgentMessage.user(content),
            AgentMessage.assistant("回复-" + content)
        ));
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOps = mock(ListOperations.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.expire(anyString(), any())).thenReturn(true);
        when(redis.delete(anyString())).thenReturn(true);
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(hashOps.increment(anyString(), any(), anyLong())).thenReturn(1L);

        List<String> buffer = new ArrayList<>();
        when(listOps.rightPush(anyString(), anyString())).thenAnswer(invocation -> {
            buffer.add(invocation.getArgument(1));
            return (long) buffer.size();
        });
        when(listOps.size(anyString())).thenAnswer(invocation -> (long) buffer.size());
        when(listOps.range(anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
            long start = invocation.getArgument(1);
            long end = invocation.getArgument(2);
            int from = Math.max(0, (int) start);
            int to = end < 0 ? buffer.size() : Math.min(buffer.size(), (int) end + 1);
            return from >= to ? List.of() : new ArrayList<>(buffer.subList(from, to));
        });
        doAnswer(invocation -> {
            long start = invocation.getArgument(1);
            if (start <= 0) {
                return null;
            }
            if (start >= buffer.size()) {
                buffer.clear();
                return null;
            }
            List<String> remaining = new ArrayList<>(buffer.subList((int) start, buffer.size()));
            buffer.clear();
            buffer.addAll(remaining);
            return null;
        }).when(listOps).trim(anyString(), anyLong(), anyLong());

        UserMemoryStore vectorStore = mock(UserMemoryStore.class);
        UserProfileSnapshotStore snapshotStore = mock(UserProfileSnapshotStore.class);
        UserMemoryBatchAnalyzer analyzer = mock(UserMemoryBatchAnalyzer.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(Optional.empty());
        UserMemoryProperties properties = new UserMemoryProperties();
        properties.setBatchSize(5);
        properties.setRelatedFactTopK(12);
        ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        UserMemoryService service = new UserMemoryService(
            vectorStore,
            snapshotStore,
            analyzer,
            embeddingClient,
            properties,
            redis,
            mapper
        );
        return new Fixture(service, vectorStore, snapshotStore, analyzer, embeddingClient, buffer);
    }

    private record Fixture(
        UserMemoryService service,
        UserMemoryStore vectorStore,
        UserProfileSnapshotStore snapshotStore,
        UserMemoryBatchAnalyzer analyzer,
        EmbeddingClient embeddingClient,
        List<String> buffer
    ) {
    }
}
