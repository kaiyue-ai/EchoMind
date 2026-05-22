package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.tool.Tool;
import com.echomind.agent.tool.ToolInvoker;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.tool.LlmTool;
import com.echomind.llm.router.ModelSpec;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
class ProviderRequestFactory {

    private final PromptComposer promptComposer;
    private final ToolExposurePlanner toolExposurePlanner;

    ProviderRequest create(PipelineContext ctx, ModelSpec model, String systemPrompt, ToolExposure exposure) {
        return new ProviderRequest(
            model,
            systemPrompt,
            promptComposer.compose(ctx),
            ctx.attachmentsForModel(),
            llmTools(exposure.tools(), ctx),
            exposure.requiredToolName(),
            toolInputMessage(ctx)
        );
    }

    private List<LlmTool> llmTools(List<Tool> tools, PipelineContext ctx) {
        return tools.stream()
            .map(tool -> new LlmTool(
                toolExposurePlanner.functionName(tool),
                tool.description(),
                tool.parameterSchema(),
                toolExposurePlanner.directResult(tool),
                argumentsJson -> new ToolInvoker(tool, ctx).call(argumentsJson)
            ))
            .toList();
    }

    private String toolInputMessage(PipelineContext ctx) {
        Object raw = ctx.getAttributes().get("rawUserMessage");
        if (raw != null && !String.valueOf(raw).isBlank()) {
            return String.valueOf(raw);
        }
        return ctx.getUserMessage();
    }
}
