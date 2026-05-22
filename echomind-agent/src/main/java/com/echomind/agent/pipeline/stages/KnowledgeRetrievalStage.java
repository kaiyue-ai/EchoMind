package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.common.model.AgentMessage;
import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.embedding.QueryEmbeddingCache;
import com.echomind.memory.knowledge.AgentKnowledgeHit;
import com.echomind.memory.knowledge.AgentKnowledgeService;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 私有知识库召回阶段。
 *
 * <p>会话记忆解决“这个对话之前说过什么”，知识库解决“这个 Agent 上传过哪些资料”。
 * 两者不能混成一份记忆，否则不同 Agent 的知识会互相污染。</p>
 */
@RequiredArgsConstructor
public class KnowledgeRetrievalStage implements PipelineStage {

    // 知识库服务
    private final AgentKnowledgeService knowledgeService;
    // 计算向量数据的客户端
    private final EmbeddingClient embeddingClient;
    // 向量搜索结果数量
    private final int topK;

    @Override
    public int order() {
        return 25;
    }

    @Override
    // 搜素与用户消息相关的向量数据库的三四条消息
    public PipelineContext process(PipelineContext ctx) {
        return QueryEmbeddingCache.getOrEmbed(ctx.getAttributes(), embeddingClient, ctx.getUserMessage())
            .map(vector -> knowledgeService.search(ctx.getAgentId(), ctx.getUserMessage(), vector, topK))
            .map(hits -> injectHits(ctx, hits))
            .orElse(ctx);
    }

    private PipelineContext injectHits(PipelineContext ctx, List<AgentKnowledgeHit> hits) {
        if (!hits.isEmpty()) {
            ctx.getAttributes().put("knowledgeHits", hits);
            ctx.getMessages().add(0, AgentMessage.system(buildKnowledgePrompt(hits)));
        }
        return ctx;
    }

    private String buildKnowledgePrompt(List<AgentKnowledgeHit> hits) {
        String body = hits.stream()
            .map(hit -> "- 来源：" + hit.fileName() + " #" + hit.chunkIndex() + "\n" + hit.content())
            .collect(Collectors.joining("\n\n"));
        return """
            === Agent Knowledge Base ===
            下面是当前 Agent 私有知识库中与用户问题最相关的资料片段。
            回答时请优先依据这些资料；如果资料不足，请明确说明资料中没有足够信息，不要编造。

            %s
            === End Agent Knowledge Base ===
            """.formatted(body);
    }
}
