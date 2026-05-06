package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.llm.provider.ModelProvider;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import com.echomind.llm.session.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

/**
 * 结果聚合阶段——管道第四阶段（order=40），负责将用户消息和Skill执行结果合成为最终响应。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>组装Prompt：将用户原始消息与Skill执行结果拼接为一个完整的提示词</li>
 *   <li>调用LLM：通过{@link ModelProvider#chat}调用选定的LLM模型生成自然语言响应</li>
 *   <li>设置最终响应：将LLM生成的文本写入{@link PipelineContext#setFinalResponse(String)}</li>
 * </ul>
 *
 * <p>Prompt构建逻辑：</p>
 * <ul>
 *   <li>始终以用户原始消息为开头</li>
 *   <li>如果有Skill结果，追加Skill执行结果并指示LLM进行合成</li>
 *   <li>如果没有Skill结果，直接用用户消息请求LLM</li>
 * </ul>
 *
 * <p>这是管道中唯一直接调用LLM进行文本生成的阶段，也是决定最终响应质量的关键环节。</p>
 *
 * @author EchoMind Team
 * @see DynamicModelRouter
 * @see ModelProvider
 */
public class ResultAggregationStage implements PipelineStage {

    /** SLF4J日志记录器，用于记录LLM调用事件和异常 */
    private static final Logger log = LoggerFactory.getLogger(ResultAggregationStage.class);

    /** 动态模型路由器，用于解析目标模型 */
    private final DynamicModelRouter router;

    /** 模型提供者注册表，用于获取实际的LLM调用接口 */
    private final ModelProviderRegistry providerRegistry;

    /**
     * 构造结果聚合阶段。
     *
     * @param router           动态模型路由器
     * @param providerRegistry 模型提供者注册表
     */
    public ResultAggregationStage(DynamicModelRouter router, ModelProviderRegistry providerRegistry) {
        this.router = router;
        this.providerRegistry = providerRegistry;
    }

    /**
     * @return 40 — 管道第四阶段，在Skill调用之后、记忆持久化之前执行
     */
    @Override
    public int order() { return 40; }

    /**
     * 聚合结果并生成最终响应。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>解析会话上下文中的模型ID</li>
     *   <li>通过{@link DynamicModelRouter}获取目标模型规格</li>
     *   <li>从{@link ModelProviderRegistry}获取对应的模型提供者</li>
     *   <li>如果找不到提供者，设置错误响应并返回</li>
     *   <li>调用{@link #buildPrompt(PipelineContext)}构建完整提示词</li>
     *   <li>调用{@link ModelProvider#chat}生成响应</li>
     *   <li>将响应设置到{@link PipelineContext#setFinalResponse(String)}</li>
     * </ol>
     *
     * @param ctx 管道上下文，包含用户消息和Skill执行结果
     * @return 设置了finalResponse的管道上下文
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
        ModelProvider provider = providerRegistry.getProvider(model.providerId())
            .orElse(null);

        if (provider == null) {
            ctx.setFinalResponse("[Error] No model provider available");
            return ctx;
        }

        String message = buildPrompt(ctx);
        try {
            String response = provider.chat(model, ctx.getSystemPrompt(), message);
            ctx.setFinalResponse(response);
        } catch (Exception e) {
            log.error("LLM call failed in aggregation stage", e);
            ctx.setFinalResponse("[Error] LLM call failed: " + e.getMessage());
        }
        return ctx;
    }

    /**
     * 构建发送给LLM的完整提示词。
     *
     * <p>构建规则：</p>
     * <ul>
     *   <li>始终以用户原始消息为开头</li>
     *   <li>如果存在Skill执行结果，追加结果并要求LLM进行信息合成</li>
     *   <li>如果没有Skill结果，直接返回用户原始消息</li>
     * </ul>
     *
     * @param ctx 管道上下文
     * @return 组装完成的提示词字符串
     */
    private String buildPrompt(PipelineContext ctx) {
        StringBuilder sb = new StringBuilder(ctx.getUserMessage());
        if (!ctx.getSkillResults().isEmpty()) {
            sb.append("\n\nSkill execution results:\n");
            sb.append(ctx.getSkillResults().stream()
                .collect(Collectors.joining("\n")));
            sb.append("\n\nPlease synthesize these results into a helpful response.");
        }
        return sb.toString();
    }
}
