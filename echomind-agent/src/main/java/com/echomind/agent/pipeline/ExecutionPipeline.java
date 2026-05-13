package com.echomind.agent.pipeline;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Agent 请求管线。
 *
 * <p>阶段按 {@link PipelineStage#order()} 排序后串行执行。任一阶段抛出异常时，
 * 管线停止，并把错误写入 {@link PipelineContext#getFinalResponse()}。</p>
 */
@Slf4j
public class ExecutionPipeline {

    private final List<PipelineStage> stages;

    public ExecutionPipeline(List<PipelineStage> stages) {
        this.stages = new ArrayList<>(stages);
        this.stages.sort(Comparator.comparingInt(PipelineStage::order));
    }

    /** 执行全部阶段。 */
    public PipelineContext execute(PipelineContext ctx) {
        return executeUpTo(ctx, Integer.MAX_VALUE);
    }

    /**
     * 执行到指定 order 为止。流式接口用它先完成上下文、模型和工具准备。
     */
    public PipelineContext executeUpTo(PipelineContext ctx, int maxOrderInclusive) {
        PipelineContext current = ctx;
        for (PipelineStage stage : stages) {
            if (stage.order() > maxOrderInclusive) break;
            try {
                log.debug("[Pipeline] Running stage {} (order={})", stage.name(), stage.order());
                current = stage.process(current);
            } catch (Exception e) {
                log.error("[Pipeline] Stage {} failed: {}", stage.name(), e.getMessage());
                current.setFinalResponse("[Error] Pipeline stage '" + stage.name() + "' failed: " + e.getMessage());
                return current;
            }
        }
        return current;
    }

    /** 按类型查找阶段，流式接口会用它定位聚合阶段。 */
    @SuppressWarnings("unchecked")
    public <T extends PipelineStage> T getStage(Class<T> stageClass) {
        for (PipelineStage stage : stages) {
            if (stageClass.isInstance(stage)) {
                return (T) stage;
            }
        }
        return null;
    }
}
