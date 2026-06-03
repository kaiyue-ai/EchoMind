package com.echomind.agent.pipeline.request;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.composing.PromptComposer;
import com.echomind.agent.pipeline.planning.ToolExposure;
import com.echomind.agent.pipeline.planning.ToolExposurePlanner;
import com.echomind.agent.tool.invoker.ToolInvoker;
import com.echomind.agent.tool.core.Tool;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.tool.LlmTool;
import com.echomind.llm.router.ModelSpec;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class ProviderRequestFactory {

    private final PromptComposer promptComposer;
    private final ToolExposurePlanner toolExposurePlanner;

    public ProviderRequest create(PipelineContext ctx, ModelSpec model, String systemPrompt, ToolExposure exposure) {
        return new ProviderRequest(
            model,
            systemPrompt,
            promptComposer.compose(ctx),
            ctx.attachmentsForModel(),
            llmTools(exposure.tools(), ctx, exposure.requiredToolName()),
            exposure.requiredToolName(),
            toolInputMessage(ctx)
        );
    }

    private List<LlmTool> llmTools(List<Tool> tools, PipelineContext ctx, String requiredToolName) {
        return tools.stream()
            .map(tool -> {
                String functionName = toolExposurePlanner.functionName(tool);
                return new LlmTool(
                    functionName,
                    tool.description(),
                    tool.parameterSchema(),
                    argumentsJson -> new ToolInvoker(tool, ctx).call(argumentsJson)
                );
            })
            .toList();
    }

    private String toolInputMessage(PipelineContext ctx) {
        Object raw = ctx.getAttributes().get(PipelineContext.ATTR_RAW_USER_MESSAGE);
        if (raw != null && !String.valueOf(raw).isBlank()) {
            return String.valueOf(raw);
        }
        return ctx.getUserMessage();
    }
}
