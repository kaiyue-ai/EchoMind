package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.common.model.AgentMessage;
import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.embedding.QueryEmbeddingCache;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import com.echomind.memory.usermemory.UserProfileSnapshot;
import com.echomind.memory.usermemory.UserProfileSnapshotStore;
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
    private final UserProfileSnapshotStore snapshotStore;
    private final boolean enabled;
    private final int topK;
    private final double minConfidence;

    @Override
    public int order() {
        return 12;
    }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        if (!enabled || ctx.getUserMemoryKey() == null || ctx.getUserMemoryKey().isBlank()) {
            log.debug("Skip user memory retrieval enabled={} userMemoryKey={}", enabled, ctx.getUserMemoryKey());
            return ctx;
        }
        snapshotStore.get(ctx.getProfileUserId()).ifPresent(snapshot -> injectSnapshot(ctx, snapshot));
        if (vectorStore == null || topK <= 0) {
            return ctx;
        }
        return QueryEmbeddingCache.getOrEmbed(ctx.getAttributes(), embeddingClient, ctx.getUserMessage())
            .map(vector -> {
                List<UserMemoryHit> hits = vectorStore.search(ctx.getUserMemoryKey(), vector, topK, minConfidence);
                log.debug("User memory retrieval userMemoryKey={} store={} hits={}",
                    ctx.getUserMemoryKey(), vectorStore.getClass().getSimpleName(), hits.size());
                return injectHits(ctx, hits);
            })
            .orElseGet(() -> {
                log.debug("User memory retrieval userMemoryKey={} skipped: query embedding unavailable", ctx.getUserMemoryKey());
                return ctx;
            });
    }

    private void injectSnapshot(PipelineContext ctx, UserProfileSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasContent()) {
            return;
        }
        ctx.getAttributes().put("userProfileSnapshot", snapshot);
        ctx.getMessages().add(0, AgentMessage.system("""
            === 用户画像快照 ===
            下面是当前用户的长期画像摘要。它是长期事实的压缩版本，用于理解用户背景、偏好和稳定约束；如果与本轮问题无关，不要强行提及。

            %s
            === End 用户画像快照 ===
            """.formatted(snapshot.content())));
    }

    private PipelineContext injectHits(PipelineContext ctx, List<UserMemoryHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return ctx;
        }
        List<UserMemoryHit> ordered = hits.stream()
            .sorted(Comparator.comparingDouble(UserMemoryHit::confidence).reversed())
            .toList();
        ctx.getAttributes().put("userMemoryHits", ordered);
        int insertAt = Math.min(1, ctx.getMessages().size());
        ctx.getMessages().add(insertAt, AgentMessage.system(buildPrompt(ordered)));
        return ctx;
    }

    private String buildPrompt(List<UserMemoryHit> hits) {
        String body = hits.stream()
            .map(hit -> "[%s] %s".formatted(hit.category().displayName(), hit.content()))
            .collect(Collectors.joining("\n"));
        return """
            === 用户相关长期事实 ===
            下面是与本轮问题相关的用户长期事实。回答时可用它理解用户背景、偏好和稳定约束；如果与本轮问题无关，不要强行提及。

            %s
            === End 用户相关长期事实 ===
            """.formatted(body);
    }
}
