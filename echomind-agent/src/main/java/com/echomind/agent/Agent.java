package com.echomind.agent;

import com.echomind.agent.pipeline.ExecutionPipeline;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.stages.MemoryPersistStage;
import com.echomind.agent.pipeline.stages.ResultAggregationStage;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import reactor.core.publisher.Flux;

/**
 * Agent 的运行时实例。
 *
 * <p>它只保存身份、系统提示词、默认模型和可用 Skill 列表；真正的请求处理交给
 * {@link ExecutionPipeline}。这样多个 Agent 可以共享同一条无状态管线。
 */
@Getter
@RequiredArgsConstructor
@Slf4j
public class Agent {

    private final String agentId;
    private final String name;
    private final String systemPrompt;
    private final String defaultModelId;
    private final List<String> skillIds;
    private final ExecutionPipeline pipeline;

    /** 执行完整管线并返回聚合后的上下文。 */
    public PipelineContext chat(PipelineContext ctx) {
        return pipeline.execute(ctx);
    }

    /**
     * 执行流式对话，返回文本片段流。
     *
     * <p>核心流程：
     * 1. 先执行前置准备阶段（上下文加载、模型解析、附件准备）
     * 2. 获取结果聚合阶段，执行流式LLM调用
     * 3. 收集所有token用于记忆保存
     * 4. 流式完成后异步保存会话记忆
     *
     * <p>关键设计：
     * - 前置阶段同步执行，确保准备工作完成后再开始流式输出
     * - token收集与流式输出并行，不阻塞客户端接收
     * - 记忆保存放在doFinally中，在流式结束后异步执行
     *
     * @param ctx 管线上下文
     * @return 文本片段流（Flux<String>）
     */
    public Flux<String> chatStream(PipelineContext ctx) {
        // 1. 执行前置准备阶段（order <= 35）
        //    - ContextEnrichStage (10): 加载会话上下文
        //    - ModelResolutionStage (20): 解析模型规格
        //    - MultimodalGuardStage (30): 多模态输入检查
        //    - AttachmentPreparationStage (35): 附件URL准备
        ctx = pipeline.executeUpTo(ctx, 35);

        // 2. 检查前置阶段是否失败
        if (ctx.hasFailed()) {
            return Flux.just(ctx.getFinalResponse());
        }

        // 3. 获取结果聚合阶段（负责LLM调用）
        ResultAggregationStage aggStage = pipeline.getStage(ResultAggregationStage.class);
        if (aggStage == null) {
            ctx.markFailed("Result aggregation stage not found");
            return Flux.just(ctx.getFinalResponse());
        }

        // 4. 用于收集所有token，以便后续保存到记忆
        StringBuilder finalResponse = new StringBuilder();
        PipelineContext streamCtx = ctx;
        Context otelContext = Context.current();

        // 5. 执行流式LLM调用并返回token流
        return aggStage.streamResponse(ctx)
            // 6. 收集每个token到finalResponse（用于记忆保存）
            .doOnNext(finalResponse::append)
            // 7. 流式结束后的处理（成功完成时保存记忆）
            .doFinally(signalType -> {
                // 设置最终响应文本
                if (!streamCtx.hasFailed()) {
                    streamCtx.setFinalResponse(finalResponse.toString());
                }

                // 仅在成功完成且启用记忆持久化时保存记忆
                if (signalType == reactor.core.publisher.SignalType.ON_COMPLETE
                    && !streamCtx.hasFailed()
                    && streamCtx.isMemoryPersistenceEnabled()) {
                    MemoryPersistStage memoryStage = pipeline.getStage(MemoryPersistStage.class);
                    if (memoryStage != null) {
                        try (Scope ignored = otelContext.makeCurrent()) {
                            memoryStage.process(streamCtx);
                        } catch (RuntimeException e) {
                            log.warn("Failed to publish streaming chat memory session={}: {}",
                                streamCtx.getSessionId(), e.getMessage());
                        }
                    }
                }
            });
    }
}
