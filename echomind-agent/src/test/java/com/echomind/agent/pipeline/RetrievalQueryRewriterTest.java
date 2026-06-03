package com.echomind.agent.pipeline;

import com.echomind.llm.provider.ModelProvider;
import com.echomind.llm.provider.ProviderStreamChunk;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.dto.ProviderResponse;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelSpec;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalQueryRewriterTest {

    @Test
    void rewritesUserMessageForRetrievalOnly() {
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        RetrievalQueryRewriter rewriter = rewriter(request -> {
            captured.set(request);
            return ProviderResponse.text("{\"query\":\"今天苹果的价格\"}");
        });
        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("今天苹果多少钱");

        String query = rewriter.queryFor(ctx);

        assertThat(query).isEqualTo("今天苹果的价格");
        assertThat(ctx.getUserMessage()).isEqualTo("今天苹果多少钱");
        assertThat(ctx.getAttributes())
            .containsEntry(RetrievalQueryRewriter.ATTR_RETRIEVAL_QUERY, "今天苹果的价格")
            .containsEntry(RetrievalQueryRewriter.ATTR_RETRIEVAL_QUERY_REWRITTEN, true);
        assertThat(captured.get().tools()).isEmpty();
    }

    @Test
    void returnsCachedRetrievalQueryWithinSamePipelineContext() {
        CountingProvider provider = new CountingProvider(request -> ProviderResponse.text("{\"query\":\"今天苹果的价格\"}"));
        RetrievalQueryRewriter rewriter = rewriter(provider);
        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("今天苹果多少钱");

        String first = rewriter.queryFor(ctx);
        String second = rewriter.queryFor(ctx);

        assertThat(first).isEqualTo("今天苹果的价格");
        assertThat(second).isEqualTo("今天苹果的价格");
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void fallsBackToOriginalOnInvalidJsonEmptyOrLongQuery() {
        PipelineContext invalid = context("今天苹果多少钱");
        assertThat(rewriter(request -> ProviderResponse.text("not-json")).queryFor(invalid))
            .isEqualTo("今天苹果多少钱");
        assertThat(invalid.getAttributes())
            .containsEntry(RetrievalQueryRewriter.ATTR_RETRIEVAL_QUERY_REWRITTEN, false);

        PipelineContext empty = context("今天苹果多少钱");
        assertThat(rewriter(request -> ProviderResponse.text("{\"query\":\"\"}")).queryFor(empty))
            .isEqualTo("今天苹果多少钱");

        PipelineContext tooLong = context("今天苹果多少钱");
        assertThat(rewriter(request -> ProviderResponse.text("{\"query\":\"超过\"}"), 1).queryFor(tooLong))
            .isEqualTo("今天苹果多少钱");
    }

    @Test
    void disabledRewriterReturnsOriginalMessage() {
        PipelineContext ctx = context("今天苹果多少钱");

        String query = RetrievalQueryRewriter.disabled().queryFor(ctx);

        assertThat(query).isEqualTo("今天苹果多少钱");
        assertThat(ctx.getAttributes())
            .containsEntry(RetrievalQueryRewriter.ATTR_RETRIEVAL_QUERY, "今天苹果多少钱")
            .containsEntry(RetrievalQueryRewriter.ATTR_RETRIEVAL_QUERY_REWRITTEN, false);
    }

    private PipelineContext context(String message) {
        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage(message);
        return ctx;
    }

    private RetrievalQueryRewriter rewriter(CountingProvider provider) {
        return rewriter(provider, 120);
    }

    private RetrievalQueryRewriter rewriter(CountingProvider provider, int maxQueryChars) {
        return new RetrievalQueryRewriter(
            new ModelSpec("mock", "rewrite-model", Set.of(ModelCapability.TEXT), true),
            provider,
            true,
            1000,
            maxQueryChars,
            null
        );
    }

    private RetrievalQueryRewriter rewriter(java.util.function.Function<ProviderRequest, ProviderResponse> handler) {
        return rewriter(new CountingProvider(handler));
    }

    private RetrievalQueryRewriter rewriter(java.util.function.Function<ProviderRequest, ProviderResponse> handler,
                                            int maxQueryChars) {
        return rewriter(new CountingProvider(handler), maxQueryChars);
    }

    private static class CountingProvider implements ModelProvider {

        private final java.util.function.Function<ProviderRequest, ProviderResponse> handler;
        private int calls;

        private CountingProvider(java.util.function.Function<ProviderRequest, ProviderResponse> handler) {
            this.handler = handler;
        }

        @Override
        public String providerId() {
            return "mock";
        }

        @Override
        public boolean supports(ModelSpec model) {
            return "mock".equals(model.providerId());
        }

        @Override
        public ProviderResponse chatWithUsage(ProviderRequest request) {
            calls++;
            return handler.apply(request);
        }

        @Override
        public Flux<ProviderStreamChunk> streamWithUsage(ProviderRequest request) {
            return Flux.just(ProviderStreamChunk.text(chatWithUsage(request).content()));
        }
    }
}
