package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelProviderRegistry;
import lombok.RequiredArgsConstructor;

/**
 * 多模态能力校验阶段。
 *
 * <p>只要本轮消息携带图片，就要求已解析模型具备 {@link ModelCapability#VISION}。
 * 这个校验在后端执行，避免前端绕过提示后把图片发给纯文本模型。</p>
 */
@RequiredArgsConstructor
public class MultimodalGuardStage implements PipelineStage {

    private final ModelProviderRegistry registry;

    @Override
    public int order() {
        return 30;
    }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        // 如果没有图片,直接放行就行
        if (!ctx.hasImageAttachments()) {
            return ctx;
        }

        // 获取模型消息
        var model = ctx.getResolvedModel();
        if (model == null) {
            String[] parts = ctx.getModelId() == null ? new String[0] : ctx.getModelId().split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("无法识别当前模型，不能处理图片消息");
            }
            model = registry.find(parts[0], parts[1])
                .orElseThrow(() -> new IllegalArgumentException("当前模型不存在: " + ctx.getModelId()));
            ctx.setResolvedModel(model);
        }
        // 检查是否包含模型多模态功能
        if (!model.capabilities().contains(ModelCapability.VISION)) {
            throw new IllegalArgumentException("当前模型不支持多模态图片输入，请切换到带 VISION 能力的模型");
        }
        return ctx;
    }
}
