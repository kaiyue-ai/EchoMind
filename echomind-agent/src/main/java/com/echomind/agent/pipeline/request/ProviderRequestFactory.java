package com.echomind.agent.pipeline.request;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.composing.PromptComposer;
import com.echomind.agent.tool.core.Tool;
import com.echomind.agent.tool.invoker.ToolInvoker;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.tool.LlmTool;
import com.echomind.llm.router.ModelSpec;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Provider 请求组装器。
 *
 * <p>它只负责把管线上下文、模型规格和已决定暴露的工具转换为 LLM 模块的中立请求对象。
 * 工具是否暴露由上游阶段决定；这里不再做关键词、Agent skillIds 或业务语义判断，
 * 以保持 Agent 管线和 Provider 适配层解耦。</p>
 */
@RequiredArgsConstructor
public class ProviderRequestFactory {

    private final PromptComposer promptComposer;

    /**
     * 创建一次模型调用请求。
     *
     * @param ctx          当前管线上下文
     * @param model        已解析出的模型规格
     * @param systemPrompt 最终系统提示词
     * @param tools        本轮实际暴露给模型的工具列表
     * @return Provider 层可直接执行的请求
     */
    public ProviderRequest create(PipelineContext ctx, ModelSpec model, String systemPrompt, List<Tool> tools) {
        return create(ctx, model, systemPrompt, tools, null);
    }

    public ProviderRequest create(PipelineContext ctx, ModelSpec model, String systemPrompt, List<Tool> tools,
                                  String requiredToolName) {
        return new ProviderRequest(
            model,
            systemPrompt,
            promptComposer.compose(ctx),
            ctx.attachmentsForModel(),
            llmTools(tools, ctx),
            requiredToolName,
            toolInputMessage(ctx)
        );
    }

    private List<LlmTool> llmTools(List<Tool> tools, PipelineContext ctx) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
            .map(tool -> {
                // Spring AI function calling 要求工具名符合函数命名规则，运行时原名仍保留在 Tool 上。
                String functionName = ToolFunctionName.from(tool);
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
