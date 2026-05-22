package com.echomind.agent.pipeline;

import com.echomind.common.observability.EchoMindTrace;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Agent 请求管线。
 *
 * <p>阶段按 {@link PipelineStage#order()} 排序后串行执行。任一阶段抛出异常时，
 * 管线停止，并把结构化错误写入 {@link PipelineContext}。</p>
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
            // 进行自定义埋点
            Span span = EchoMindTrace.startSpan("echomind.pipeline.stage");
            span.setAttribute("echomind.pipeline.stage", stage.name());
            span.setAttribute("echomind.pipeline.order", stage.order());
            span.setAttribute("echomind.user_id", safe(current.getUserId()));
            span.setAttribute("echomind.agent_id", safe(current.getAgentId()));
            span.setAttribute("echomind.session_id", safe(current.getSessionId()));
            span.setAttribute("echomind.model_id", safe(current.getModelId()));
            try {
                try (Scope ignored = span.makeCurrent()) {
                    log.debug("[Pipeline] Running stage {} (order={})", stage.name(), stage.order());
                    current = stage.process(current);
                    // 确保每个阶段都有 traceId
                    if (current.getTraceId() == null || current.getTraceId().isBlank()) {
                        current.setTraceId(EchoMindTrace.traceId(span));
                    }
                }
            } catch (Exception e) {
                EchoMindTrace.recordException(span, e);
                log.error("[Pipeline] Stage {} failed: {}", stage.name(), e.getMessage());
                current.markFailed("Pipeline stage '" + stage.name() + "' failed: " + e.getMessage());
                return current;
            } finally {
                span.end(); //结束埋点,将埋点打包给 OpenTelemetry Collector
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

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
