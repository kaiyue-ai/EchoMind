package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.common.model.AgentMessage;
import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.embedding.QueryEmbeddingCache;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** 按用户隔离后的 memoryKey 召回用户长期画像，并注入本轮模型上下文。 */
@Slf4j
@RequiredArgsConstructor
public class UserMemoryRetrievalStage implements PipelineStage {

    private final EmbeddingClient embeddingClient;
    private final UserMemoryStore vectorStore;
    private final boolean enabled;
    private final int topK;
    private final double minConfidence;

    @Override
    public int order() {
        return 12;
    }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        if (!enabled || vectorStore == null || topK <= 0 || ctx.getMemoryKey() == null || ctx.getMemoryKey().isBlank()) {
            log.debug("Skip user memory retrieval enabled={} store={} topK={} sessionId={}",
                enabled, vectorStore == null ? "null" : vectorStore.getClass().getSimpleName(), topK, ctx.getMemoryKey());
            return ctx;
        }
        return QueryEmbeddingCache.getOrEmbed(ctx.getAttributes(), embeddingClient, ctx.getUserMessage())
            .map(vector -> {
                List<UserMemoryHit> hits = vectorStore.search(ctx.getMemoryKey(), vector, topK, minConfidence);
                log.debug("User memory retrieval sessionId={} store={} hits={}",
                    ctx.getMemoryKey(), vectorStore.getClass().getSimpleName(), hits.size());
                return injectHits(ctx, hits);
            })
            .orElseGet(() -> {
                log.debug("User memory retrieval sessionId={} skipped: query embedding unavailable", ctx.getMemoryKey());
                return ctx;
            });
    }

    private PipelineContext injectHits(PipelineContext ctx, List<UserMemoryHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return ctx;
        }
        List<UserMemoryHit> ordered = hits.stream()
            .sorted(Comparator.comparingDouble(UserMemoryHit::confidence).reversed())
            .toList();
        ctx.getAttributes().put("userMemoryHits", ordered);
        ctx.getMessages().add(0, AgentMessage.system(buildPrompt(ordered)));
        return ctx;
    }

    private String buildPrompt(List<UserMemoryHit> hits) {
        String body = hits.stream()
            .map(hit -> "[%s] %s".formatted(hit.category().displayName(), hit.content()))
            .collect(Collectors.joining("\n"));
        return """
            === 用户长期画像 ===
            下面是当前 session 中沉淀的用户长期画像。回答时可用它理解用户背景、偏好和近期关注；如果与本轮问题无关，不要强行提及。

            %s
            === End 用户长期画像 ===
            """.formatted(body);
    }
}
