package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelSpec;
import com.echomind.llm.session.SessionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 解析本轮请求使用的模型。
 *
 * <p>工具不在本阶段执行。可用 Skill 和外部 MCP 工具会在 {@link ResultAggregationStage}
 * 中注册为模型函数，由模型根据本轮上下文自主决定是否调用。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ModelResolutionStage implements PipelineStage {

    private final DynamicModelRouter router;

    @Override
    public int order() { return 20; }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        SessionContext sessionCtx = SessionContext.create(ctx.getSessionId());
        // 获取对应供应商的对应模型
        if (ctx.getModelId() != null) {
            String[] parts = ctx.getModelId().split(":");
            if (parts.length == 2) {
                sessionCtx = sessionCtx.withModel(parts[0], parts[1]);
            }
        }
        // 模型解析
        ModelSpec model = router.resolve(sessionCtx);
        // 设置模型
        ctx.setResolvedModel(model);
        // 设置模型 ID
        ctx.setModelId(model.providerId() + ":" + model.modelName());
        // 记录模型
        log.debug("Resolved model: {}", ctx.getModelId());
        return ctx;
    }
}
