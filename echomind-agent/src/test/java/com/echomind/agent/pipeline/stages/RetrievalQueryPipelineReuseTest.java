package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.RetrievalQueryRewriter;
import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.knowledge.AgentKnowledgeService;
import com.echomind.memory.usermemory.UserMemoryStore;
import com.echomind.memory.usermemory.UserProfileSnapshotStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalQueryPipelineReuseTest {

    @Test
    void userMemoryAndKnowledgeStagesShareRewrittenQueryAndEmbedding() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        UserMemoryStore userMemoryStore = mock(UserMemoryStore.class);
        AgentKnowledgeService knowledgeService = mock(AgentKnowledgeService.class);
        CountingRewriter rewriter = new CountingRewriter("今天苹果的价格");
        double[] vector = new double[] {0.8, 0.2};
        when(embeddingClient.embed("今天苹果的价格")).thenReturn(Optional.of(vector));
        when(userMemoryStore.search("user:default", vector, 5, 0.3, 0.40)).thenReturn(List.of());
        when(knowledgeService.search("agent-1", "今天苹果多少钱", vector, 4)).thenReturn(List.of());

        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setAgentId("agent-1");
        ctx.setUserMessage("今天苹果多少钱");
        new UserMemoryRetrievalStage(
            embeddingClient,
            userMemoryStore,
            UserProfileSnapshotStore.noop(),
            true,
            5,
            0.3,
            rewriter
        ).process(ctx);
        new KnowledgeRetrievalStage(knowledgeService, embeddingClient, 4, rewriter).process(ctx);

        assertThat(rewriter.calls).isEqualTo(1);
        verify(embeddingClient, times(1)).embed("今天苹果的价格");
        verify(userMemoryStore).search("user:default", vector, 5, 0.3, 0.40);
        verify(knowledgeService).search("agent-1", "今天苹果多少钱", vector, 4);
    }

    private static class CountingRewriter extends RetrievalQueryRewriter {

        private final String query;
        private int calls;

        private CountingRewriter(String query) {
            super(null, null, false, 1, 120, null);
            this.query = query;
        }

        @Override
        public String queryFor(PipelineContext ctx) {
            Object cached = ctx.getAttributes().get(ATTR_RETRIEVAL_QUERY);
            if (cached instanceof String value) {
                return value;
            }
            calls++;
            ctx.getAttributes().put(ATTR_RETRIEVAL_QUERY, query);
            ctx.getAttributes().put(ATTR_RETRIEVAL_QUERY_REWRITTEN, true);
            return query;
        }
    }
}
