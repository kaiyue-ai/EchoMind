package com.echomind.usermemory.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MemoryDecision;
import com.echomind.common.model.UserMemoryEvent;
import com.echomind.memory.usermemory.UserMemoryCategory;
import com.echomind.memory.usermemory.UserMemoryEntry;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import com.echomind.memory.usermemory.UserProfileSnapshot;
import com.echomind.memory.usermemory.UserProfileSnapshotStore;
import com.echomind.usermemory.config.UserMemoryProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryServiceTest {

    @Test
    void skipsWhenDecisionDisablesBothFactAndProfile() {
        Fixture fixture = fixture();

        fixture.service().ingest(event(MemoryDecision.NONE));

        verify(fixture.analyzer(), never()).analyze(anyString(), anyString(), any(), any(), eq(false), eq(false));
        verify(fixture.vectorStore(), never()).save(any(UserMemoryEntry.class));
        verify(fixture.snapshotStore(), never()).save(any(UserProfileSnapshot.class));
    }

    @Test
    void processesOneTurnImmediatelyAndWritesFactsAndProfileSnapshot() {
        Fixture fixture = fixture();
        Instant oldFirstObservedAt = Instant.parse("2026-01-01T00:00:00Z");
        when(fixture.vectorStore().search(eq("user:user-a"), any(), eq(12), eq(0.3), eq(0.65))).thenReturn(List.of(
            new UserMemoryHit("old-fact", UserMemoryCategory.PREFERENCE, "用户喜欢英文注释", "", 0.8,
                oldFirstObservedAt, oldFirstObservedAt, oldFirstObservedAt, 0.9)
        ));
        when(fixture.snapshotStore().get("user-a")).thenReturn(Optional.of(
            new UserProfileSnapshot("user-a", "旧画像", 1, null)
        ));
        when(fixture.analyzer().analyze(eq("user:user-a"), eq("旧画像"), any(), any(), eq(true), eq(true))).thenReturn(
            new UserMemoryAnalysisResult(
                List.of(new UserMemoryAnalysisResult.FactToAdd("用户希望注释使用中文", UserMemoryCategory.PREFERENCE, "用户明确要求", 0.9)),
                List.of(new UserMemoryAnalysisResult.FactToUpdate("old-fact", "用户现在希望注释使用中文", UserMemoryCategory.PREFERENCE, "用户明确要求", 0.9)),
                List.of(),
                "用户维护 EchoMind，偏好中文注释。"
            )
        );

        fixture.service().ingest(event(new MemoryDecision(true, true, true, "长期偏好")));

        verify(fixture.vectorStore()).deleteEntry("user:user-a", "old-fact");
        verify(fixture.vectorStore(), org.mockito.Mockito.times(2)).save(any(UserMemoryEntry.class));
        verify(fixture.snapshotStore()).save(org.mockito.ArgumentMatchers.argThat(snapshot ->
            snapshot.userId().equals("user-a") && snapshot.content().contains("中文注释")
        ));

        ArgumentCaptor<UserMemoryEntry> entryCaptor = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(fixture.vectorStore(), org.mockito.Mockito.times(2)).save(entryCaptor.capture());
        UserMemoryEntry updated = entryCaptor.getAllValues().stream()
            .filter(entry -> entry.entryId().equals("old-fact"))
            .findFirst()
            .orElseThrow();
        assertThat(updated.firstObservedAt()).isEqualTo(oldFirstObservedAt);
        assertThat(updated.lastObservedAt()).isAfter(oldFirstObservedAt);
        assertThat(updated.updatedAt()).isAfter(oldFirstObservedAt);
    }

    @Test
    void refreshesOnlyProfileWhenFactDecisionIsFalse() {
        Fixture fixture = fixture();
        when(fixture.analyzer().analyze(eq("user:user-a"), eq(""), any(), any(), eq(false), eq(true))).thenReturn(
            new UserMemoryAnalysisResult(List.of(), List.of(), List.of(), "新画像")
        );

        fixture.service().ingest(event(new MemoryDecision(false, true, true, "只刷新画像")));

        verify(fixture.vectorStore(), never()).save(any(UserMemoryEntry.class));
        verify(fixture.snapshotStore()).save(org.mockito.ArgumentMatchers.argThat(snapshot ->
            snapshot.content().equals("新画像")
        ));
    }

    @Test
    void analyzesOnlyUserMessagesSoAssistantHallucinationsCannotBecomeEvidence() {
        Fixture fixture = fixture();
        when(fixture.vectorStore().search(eq("user:user-a"), eq("我叫秦墨舟"), eq(12), eq(0.3), eq(0.65))).thenReturn(List.of());
        when(fixture.analyzer().analyze(eq("user:user-a"), eq(""), any(), any(), eq(true), eq(true))).thenReturn(
            new UserMemoryAnalysisResult(List.of(), List.of(), List.of(), "")
        );

        fixture.service().ingest(new UserMemoryEvent("user-a", "session-1", "agent-1", List.of(
            AgentMessage.user("我叫秦墨舟"),
            AgentMessage.assistant("你希望被称呼为陌尘")
        ), new MemoryDecision(true, true, true, "长期事实")));

        ArgumentCaptor<UserMemoryTurn> turnCaptor = ArgumentCaptor.forClass(UserMemoryTurn.class);
        verify(fixture.analyzer()).analyze(eq("user:user-a"), eq(""), turnCaptor.capture(), any(), eq(true), eq(true));
        assertThat(turnCaptor.getValue().messages())
            .extracting(AgentMessage::content)
            .containsExactly("我叫秦墨舟");
        verify(fixture.vectorStore()).search("user:user-a", "我叫秦墨舟", 12, 0.3, 0.65);
    }

    private UserMemoryEvent event(MemoryDecision decision) {
        return new UserMemoryEvent("user-a", "session-1", "agent-1", List.of(
            AgentMessage.user("以后注释用中文"),
            AgentMessage.assistant("好的")
        ), decision);
    }

    private Fixture fixture() {
        UserMemoryStore vectorStore = mock(UserMemoryStore.class);
        UserProfileSnapshotStore snapshotStore = mock(UserProfileSnapshotStore.class);
        UserMemoryAnalyzer analyzer = mock(UserMemoryAnalyzer.class);
        UserMemoryProperties properties = new UserMemoryProperties();
        properties.setRelatedFactTopK(12);
        properties.setMinConfidence(0.3);
        properties.setMergeMinSimilarity(0.65);
        UserMemoryService service = new UserMemoryService(
            vectorStore,
            snapshotStore,
            analyzer,
            properties
        );
        return new Fixture(service, vectorStore, snapshotStore, analyzer);
    }

    private record Fixture(
        UserMemoryService service,
        UserMemoryStore vectorStore,
        UserProfileSnapshotStore snapshotStore,
        UserMemoryAnalyzer analyzer
    ) {
    }
}
