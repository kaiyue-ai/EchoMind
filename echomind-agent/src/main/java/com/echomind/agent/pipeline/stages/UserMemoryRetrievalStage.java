package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.agent.pipeline.RetrievalQueryRewriter;
import com.echomind.common.model.AgentMessage;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import com.echomind.memory.usermemory.UserProfileSnapshot;
import com.echomind.memory.usermemory.UserProfileSnapshotStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** 按用户隔离后的 memoryKey 召回用户长期画像，并注入本轮模型上下文。 */
@Slf4j
public class UserMemoryRetrievalStage implements PipelineStage {

    // 用户长期记忆的向量存储,用向量搜索跟用户相关的历史事实
    private final UserMemoryStore vectorStore;
    // 用户画像快照存储,存的是压缩后的长期用户画像摘要
    private final UserProfileSnapshotStore snapshotStore;
    // 如果这个成员变量为flase,则直接跳过整个流程
    private final boolean enabled;
    // 搜索结果数量
    private final int topK;
    // 向量检索的最低执行度阈值,低于这个值回直接过滤掉
    private final double minConfidence;
    // 向量检索的最低相似度阈值，避免距离过远的事实乱入上下文
    private final double minSimilarity;
    private final RetrievalQueryRewriter queryRewriter;

    public UserMemoryRetrievalStage(UserMemoryStore vectorStore,
                                    UserProfileSnapshotStore snapshotStore,
                                    boolean enabled,
                                    int topK,
                                    double minConfidence) {
        this(vectorStore, snapshotStore, enabled, topK, minConfidence,
            0.40, RetrievalQueryRewriter.disabled());
    }

    public UserMemoryRetrievalStage(UserMemoryStore vectorStore,
                                    UserProfileSnapshotStore snapshotStore,
                                    boolean enabled,
                                    int topK,
                                    double minConfidence,
                                    RetrievalQueryRewriter queryRewriter) {
        this(vectorStore, snapshotStore, enabled, topK, minConfidence,
            0.40, queryRewriter);
    }

    public UserMemoryRetrievalStage(UserMemoryStore vectorStore,
                                    UserProfileSnapshotStore snapshotStore,
                                    boolean enabled,
                                    int topK,
                                    double minConfidence,
                                    double minSimilarity,
                                    RetrievalQueryRewriter queryRewriter) {
        this.vectorStore = vectorStore;
        this.snapshotStore = snapshotStore;
        this.enabled = enabled;
        this.topK = topK;
        this.minConfidence = minConfidence;
        this.minSimilarity = clampSimilarity(minSimilarity);
        this.queryRewriter = queryRewriter == null ? RetrievalQueryRewriter.disabled() : queryRewriter;
    }

    @Override
    public int order() {
        return 12;
    }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        // 如果不需要获取用户画像和历史事实的记忆就直接跳过
        if (!enabled || ctx.getUserMemoryKey() == null || ctx.getUserMemoryKey().isBlank()) {
            log.debug("Skip user memory retrieval enabled={} userMemoryKey={}", enabled, ctx.getUserMemoryKey());
            return ctx;
        }
        // 获取用户画像,如果存在,就直接存入系统提示词里面,作为依据
        snapshotStore.get(ctx.getProfileUserId()).ifPresent(snapshot -> injectSnapshot(ctx, snapshot));
        if (vectorStore == null || topK <= 0) {
            return ctx;
        }
        // 从历史事实向量数据库
        String retrievalQuery = queryRewriter.queryFor(ctx);
        List<UserMemoryHit> hits = vectorStore.search(ctx.getUserMemoryKey(), retrievalQuery, topK, minConfidence,
            minSimilarity);
        log.debug("User memory retrieval userMemoryKey={} store={} hits={}",
            ctx.getUserMemoryKey(), vectorStore.getClass().getSimpleName(), hits.size());
        return injectHits(ctx, hits);
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

    private double clampSimilarity(double value) {
        return Math.max(-1, Math.min(1, value));
    }
}
