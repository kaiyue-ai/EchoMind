package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.RetrievalQueryRewriter;
import com.echomind.memory.knowledge.AgentKnowledgeHit;
import com.echomind.memory.knowledge.AgentKnowledgeService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalStageTest {

    @Test
    void usesRetrievalQueryForKnowledgeSearchWithoutChangingUserMessage() {
        AgentKnowledgeService knowledgeService = mock(AgentKnowledgeService.class);
        when(knowledgeService.search("agent-1", "今天苹果的价格", 4)).thenReturn(List.of(
            new AgentKnowledgeHit(1L, 2L, "market.txt", 0, "苹果价格资料", 0.9)
        ));
        KnowledgeRetrievalStage stage = new KnowledgeRetrievalStage(
            knowledgeService,
            4,
            staticRewriter("今天苹果的价格")
        );
        PipelineContext ctx = new PipelineContext();
        ctx.setAgentId("agent-1");
        ctx.setUserMessage("今天苹果多少钱");

        stage.process(ctx);

        verify(knowledgeService).search("agent-1", "今天苹果的价格", 4);
        assertThat(ctx.getUserMessage()).isEqualTo("今天苹果多少钱");
        assertThat(ctx.getMessages()).hasSize(1);
        assertThat(ctx.getMessages().get(0).content()).contains("苹果价格资料");
    }

    @Test
    void skipsForInternalExecutionWhenMemoryPersistenceIsDisabled() {
        AgentKnowledgeService knowledgeService = mock(AgentKnowledgeService.class);
        AtomicInteger rewriteCalls = new AtomicInteger();
        KnowledgeRetrievalStage stage = new KnowledgeRetrievalStage(
            knowledgeService,
            4,
            countingRewriter(rewriteCalls)
        );
        PipelineContext ctx = new PipelineContext();
        ctx.setAgentId("agent-1");
        ctx.setUserMessage("今天苹果多少钱");
        ctx.setMemoryPersistenceEnabled(false);

        stage.process(ctx);

        assertThat(rewriteCalls).hasValue(0);
        verify(knowledgeService, never()).search("agent-1", "今天苹果的价格", 4);
        assertThat(ctx.getMessages()).isEmpty();
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

    private RetrievalQueryRewriter countingRewriter(AtomicInteger calls) {
        return new RetrievalQueryRewriter(null, null, false, 1, 120, null) {
            @Override
            public String queryFor(PipelineContext ctx) {
                calls.incrementAndGet();
                return "今天苹果的价格";
            }
        };
    }
}
