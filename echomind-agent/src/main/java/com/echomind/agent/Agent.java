package com.echomind.agent;

import com.echomind.agent.pipeline.ExecutionPipeline;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.stages.MemoryPersistStage;
import com.echomind.agent.pipeline.stages.ResultAggregationStage;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
     * 流式对话会先跑完记忆和模型解析阶段，再由聚合阶段返回 token 流。
     */
    public Flux<String> chatStream(PipelineContext ctx) {
        ctx = pipeline.executeUpTo(ctx, 35);
        if (ctx.hasFailed()) {
            return Flux.just(ctx.getFinalResponse());
        }
        ResultAggregationStage aggStage = pipeline.getStage(ResultAggregationStage.class);
        if (aggStage == null) {
            ctx.markFailed("Result aggregation stage not found");
            return Flux.just(ctx.getFinalResponse());
        }
        StringBuilder finalResponse = new StringBuilder();
        PipelineContext streamCtx = ctx;
        return aggStage.streamResponse(ctx)
            .doOnNext(finalResponse::append)
            .doFinally(signalType -> {
                streamCtx.setFinalResponse(finalResponse.toString());
                if (signalType == reactor.core.publisher.SignalType.ON_COMPLETE && streamCtx.isMemoryPersistenceEnabled()) {
                    MemoryPersistStage memoryStage = pipeline.getStage(MemoryPersistStage.class);
                    if (memoryStage != null) {
                        try {
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
