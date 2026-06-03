package com.echomind.agent.pipeline;

import com.echomind.llm.provider.ModelProvider;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.dto.ProviderResponse;
import com.echomind.llm.router.ModelSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Rewrites user text into a compact query used only for vector retrieval. */
public class RetrievalQueryRewriter {

    public static final String ATTR_RETRIEVAL_QUERY = "retrievalQuery";
    public static final String ATTR_RETRIEVAL_QUERY_REWRITTEN = "retrievalQueryRewritten";
    public static final String ATTR_RETRIEVAL_QUERY_FAILURE_REASON = "retrievalQueryFailureReason";

    private static final RetrievalQueryRewriter DISABLED = new RetrievalQueryRewriter(
        null, null, false, 0, 0, new ObjectMapper()
    );

    private final ModelSpec model;
    private final ModelProvider provider;
    private final boolean enabled;
    private final int timeoutMs;
    private final int maxQueryChars;
    private final ObjectMapper mapper;

    public RetrievalQueryRewriter(ModelSpec model,
                                  ModelProvider provider,
                                  boolean enabled,
                                  int timeoutMs,
                                  int maxQueryChars,
                                  ObjectMapper mapper) {
        this.model = model;
        this.provider = provider;
        this.enabled = enabled;
        this.timeoutMs = Math.max(1, timeoutMs);
        this.maxQueryChars = Math.max(1, maxQueryChars);
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    public static RetrievalQueryRewriter disabled() {
        return DISABLED;
    }

    public String queryFor(PipelineContext ctx) {
        String original = normalize(ctx == null ? null : ctx.getUserMessage());
        if (ctx == null || original.isEmpty()) {
            return original;
        }
        Object cached = ctx.getAttributes().get(ATTR_RETRIEVAL_QUERY);
        if (cached instanceof String query && !query.isBlank()) {
            return query;
        }
        if (!enabled) {
            return remember(ctx, original, false, null);
        }
        if (model == null || provider == null) {
            return remember(ctx, original, false, "rewrite model unavailable");
        }
        CompletableFuture<ProviderResponse> future = CompletableFuture
            .supplyAsync(() -> provider.chatWithUsage(request(original)));
        try {
            ProviderResponse response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (response.failed()) {
                return remember(ctx, original, false, response.failureReason());
            }
            String rewritten = parseQuery(response.content());
            if (rewritten.isEmpty()) {
                return remember(ctx, original, false, "empty rewrite query");
            }
            if (rewritten.length() > maxQueryChars) {
                return remember(ctx, original, false, "rewrite query too long");
            }
            boolean changed = !rewritten.equals(original);
            return remember(ctx, changed ? rewritten : original, changed, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return remember(ctx, original, false, failureReason(e));
        } catch (Exception e) {
            future.cancel(true);
            return remember(ctx, original, false, failureReason(e));
        }
    }

    private ProviderRequest request(String original) {
        return new ProviderRequest(
            model,
            """
                You rewrite user questions into concise semantic search queries for vector retrieval.
                Return strict JSON only: {"query":"..."}.
                Keep named entities, dates, quantities, and constraints. Do not answer the question.
                """,
            "用户原句：%s".formatted(original),
            List.of(),
            List.of()
        );
    }

    private String parseQuery(String content) throws Exception {
        JsonNode root = mapper.readTree(content == null ? "" : content.trim());
        return normalize(root.path("query").asText(""));
    }

    private String remember(PipelineContext ctx, String query, boolean rewritten, String failureReason) {
        ctx.getAttributes().put(ATTR_RETRIEVAL_QUERY, query);
        ctx.getAttributes().put(ATTR_RETRIEVAL_QUERY_REWRITTEN, rewritten);
        if (failureReason != null && !failureReason.isBlank()) {
            ctx.getAttributes().put(ATTR_RETRIEVAL_QUERY_FAILURE_REASON, failureReason);
        } else {
            ctx.getAttributes().remove(ATTR_RETRIEVAL_QUERY_FAILURE_REASON);
        }
        return query;
    }

    private String normalize(String value) {
        return Optional.ofNullable(value).orElse("").trim();
    }

    private String failureReason(Exception error) {
        if (error == null) {
            return "rewrite failed";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
