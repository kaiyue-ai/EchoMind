package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.RetrievalQueryRewriter;
import com.echomind.memory.usermemory.UserMemoryCategory;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import com.echomind.memory.usermemory.UserProfileSnapshot;
import com.echomind.memory.usermemory.UserProfileSnapshotStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryRetrievalStageTest {

    @Test
    void skipsWhenDisabled() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        UserMemoryRetrievalStage stage = new UserMemoryRetrievalStage(
            store, UserProfileSnapshotStore.noop(), false, 5, 0.3);
        PipelineContext ctx = context();

        stage.process(ctx);

        verify(store, never()).search("user:default", "我喜欢简洁代码", 5, 0.3, 0.40);
        assertThat(ctx.getMessages()).isEmpty();
    }

    @Test
    void skipsWhenVectorStoreUnavailable() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        UserMemoryRetrievalStage stage = new UserMemoryRetrievalStage(
            null, UserProfileSnapshotStore.noop(), true, 5, 0.3);
        PipelineContext ctx = context();

        stage.process(ctx);

        verify(store, never()).search("user:default", "我喜欢简洁代码", 5, 0.3, 0.40);
        assertThat(ctx.getMessages()).isEmpty();
    }

    @Test
    void injectsUserProfileHits() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        when(store.search("user:default", "我喜欢简洁代码", 5, 0.3, 0.40)).thenReturn(List.of(
            new UserMemoryHit("entry-1", UserMemoryCategory.PREFERENCE, "用户喜欢简洁代码", "用户说喜欢简洁代码", 0.9, 0.8)
        ));
        UserMemoryRetrievalStage stage = new UserMemoryRetrievalStage(
            store, UserProfileSnapshotStore.noop(), true, 5, 0.3);
        PipelineContext ctx = context();

        stage.process(ctx);

        assertThat(ctx.getMessages()).hasSize(1);
        assertThat(ctx.getMessages().get(0).role()).isEqualTo("system");
        assertThat(ctx.getMessages().get(0).content()).contains("用户相关长期事实", "用户喜欢简洁代码");
        assertThat(ctx.getAttributes()).containsKey("userMemoryHits");
    }

    @Test
    void injectsProfileSnapshotBeforeRelatedFacts() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        UserProfileSnapshotStore snapshotStore = mock(UserProfileSnapshotStore.class);
        when(snapshotStore.get("default")).thenReturn(Optional.of(
            new UserProfileSnapshot("default", "用户长期使用 Windows 和 PowerShell", 1, Instant.now())
        ));
        when(store.search("user:default", "我喜欢简洁代码", 5, 0.3, 0.40)).thenReturn(List.of(
            new UserMemoryHit("entry-1", UserMemoryCategory.PREFERENCE, "用户喜欢简洁代码", "用户说喜欢简洁代码", 0.9, 0.8)
        ));
        UserMemoryRetrievalStage stage = new UserMemoryRetrievalStage(
            store, snapshotStore, true, 5, 0.3);
        PipelineContext ctx = context();

        stage.process(ctx);

        assertThat(ctx.getMessages()).hasSize(2);
        assertThat(ctx.getMessages().get(0).content()).contains("用户画像快照", "Windows");
        assertThat(ctx.getMessages().get(1).content()).contains("用户相关长期事实", "简洁代码");
    }

    @Test
    void usesRetrievalQueryForVectorSearchWithoutChangingUserMessage() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        UserMemoryRetrievalStage stage = new UserMemoryRetrievalStage(
            store,
            UserProfileSnapshotStore.noop(),
            true,
            5,
            0.3,
            staticRewriter("今天苹果的价格")
        );
        PipelineContext ctx = context();
        ctx.setUserMessage("今天苹果多少钱");

        stage.process(ctx);

        verify(store).search("user:default", "今天苹果的价格", 5, 0.3, 0.40);
        verify(store, never()).search("user:default", "今天苹果多少钱", 5, 0.3, 0.40);
        assertThat(ctx.getUserMessage()).isEqualTo("今天苹果多少钱");
    }

    private PipelineContext context() {
        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setUserMessage("我喜欢简洁代码");
        return ctx;
    }

    private RetrievalQueryRewriter staticRewriter(String query) {
        return new RetrievalQueryRewriter(null, null, false, 1, 120, null) {
            @Override
            public String queryFor(PipelineContext ctx) {
                ctx.getAttributes().put(ATTR_RETRIEVAL_QUERY, query);
                ctx.getAttributes().put(ATTR_RETRIEVAL_QUERY_REWRITTEN, true);
                return query;
            }
        };
    }
}
