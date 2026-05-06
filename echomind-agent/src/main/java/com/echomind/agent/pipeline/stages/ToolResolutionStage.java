package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelSpec;
import com.echomind.llm.session.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具/模型解析阶段——管道第二阶段（order=20），负责解析和确定当前请求使用的LLM模型。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>根据会话上下文和Agent配置解析目标模型</li>
 *   <li>通过{@link DynamicModelRouter}进行动态模型路由</li>
 *   <li>将解析后的模型ID更新到{@link PipelineContext}中</li>
 * </ul>
 *
 * <p>模型选择逻辑：</p>
 * <ol>
 *   <li>创建{@link SessionContext}会话上下文</li>
 *   <li>如果上下文中已有modelId（格式为{@code providerId:modelName}），提取并设置到会话上下文</li>
 *   <li>调用{@link DynamicModelRouter#resolve(SessionContext)}获取最优模型</li>
 *   <li>将解析结果写回上下文（格式：{@code providerId:modelName}）</li>
 * </ol>
 *
 * <p>阶段命名：虽然叫ToolResolutionStage，但当前实现主要负责模型解析。
 * 工具解析的能力预留在此阶段，后续可扩展为同时解析可用的Skill/MCP工具。</p>
 *
 * @author EchoMind Team
 * @see DynamicModelRouter
 * @see ModelSpec
 */
public class ToolResolutionStage implements PipelineStage {

    /** SLF4J日志记录器，用于记录模型解析结果 */
    private static final Logger log = LoggerFactory.getLogger(ToolResolutionStage.class);

    /** 动态模型路由器，根据会话上下文选择最优LLM模型 */
    private final DynamicModelRouter router;

    /**
     * 构造工具/模型解析阶段。
     *
     * @param router 动态模型路由器
     */
    public ToolResolutionStage(DynamicModelRouter router) {
        this.router = router;
    }

    /**
     * @return 20 — 管道第二阶段，在上下文丰富之后执行
     */
    @Override
    public int order() { return 20; }

    /**
     * 解析目标模型并更新上下文。
     *
     * <p>将{@link PipelineContext#getModelId()}解析为providerId和modelName两部分，
     * 传递给{@link DynamicModelRouter}进行路由决策，然后将解析结果回写到上下文中。</p>
     *
     * @param ctx 管道上下文（modelId在此时应已由调用方设置）
     * @return 更新了modelId的管道上下文
     */
    @Override
    public PipelineContext process(PipelineContext ctx) {
        SessionContext sessionCtx = SessionContext.create(ctx.getSessionId());
        if (ctx.getModelId() != null) {
            String[] parts = ctx.getModelId().split(":");
            if (parts.length == 2) {
                sessionCtx = sessionCtx.withModel(parts[0], parts[1]);
            }
        }
        ModelSpec model = router.resolve(sessionCtx);
        ctx.setModelId(model.providerId() + ":" + model.modelName());
        log.debug("Resolved model: {}", ctx.getModelId());
        return ctx;
    }
}
