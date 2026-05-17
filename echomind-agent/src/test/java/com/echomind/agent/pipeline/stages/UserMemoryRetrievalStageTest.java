package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.usermemory.UserMemoryCategory;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import org.junit.jupiter.api.Test;

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
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        UserMemoryStore store = mock(UserMemoryStore.class);
        UserMemoryRetrievalStage stage = new UserMemoryRetrievalStage(embeddingClient, store, false, 5, 0.3);
        PipelineContext ctx = context();

        stage.process(ctx);

        verify(embeddingClient, never()).embed("我喜欢简洁代码");
        assertThat(ctx.getMessages()).isEmpty();
    }

    @Test
    void skipsWhenEmbeddingUnavailable() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        UserMemoryStore store = mock(UserMemoryStore.class);
        when(embeddingClient.embed("我喜欢简洁代码")).thenReturn(Optional.empty());
        UserMemoryRetrievalStage stage = new UserMemoryRetrievalStage(embeddingClient, store, true, 5, 0.3);
        PipelineContext ctx = context();

        stage.process(ctx);

        verify(store, never()).search("default:session-1", new double[] {1}, 5, 0.3);
        assertThat(ctx.getMessages()).isEmpty();
    }

    @Test
    void injectsUserProfileHits() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        UserMemoryStore store = mock(UserMemoryStore.class);
        double[] vector = new double[] {0.1, 0.2};
        when(embeddingClient.embed("我喜欢简洁代码")).thenReturn(Optional.of(vector));
        when(store.search("default:session-1", vector, 5, 0.3)).thenReturn(List.of(
            new UserMemoryHit("entry-1", UserMemoryCategory.PREFERENCE, "用户喜欢简洁代码", "用户说喜欢简洁代码", 0.9, 0.8)
        ));
        UserMemoryRetrievalStage stage = new UserMemoryRetrievalStage(embeddingClient, store, true, 5, 0.3);
        PipelineContext ctx = context();

        stage.process(ctx);

        assertThat(ctx.getMessages()).hasSize(1);
        assertThat(ctx.getMessages().get(0).role()).isEqualTo("system");
        assertThat(ctx.getMessages().get(0).content()).contains("用户长期画像", "用户喜欢简洁代码");
        assertThat(ctx.getAttributes()).containsKey("userMemoryHits");
    }

    private PipelineContext context() {
        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setUserMessage("我喜欢简洁代码");
        return ctx;
    }
}
