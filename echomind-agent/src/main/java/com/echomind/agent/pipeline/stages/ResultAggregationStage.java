package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.agent.pipeline.composing.PromptBudget;
import com.echomind.agent.pipeline.composing.PromptComposer;
import com.echomind.agent.pipeline.planning.MemoryDecisionParser;
import com.echomind.agent.pipeline.request.ProviderRequestFactory;
import com.echomind.agent.pipeline.request.ToolFunctionName;
import com.echomind.agent.tool.core.Tool;
import com.echomind.agent.tool.router.ToolRouter;
import com.echomind.common.model.TokenUsage;
import com.echomind.common.observability.EchoMindTrace;
import com.echomind.llm.provider.ModelProvider;
import com.echomind.llm.provider.dto.ProviderResponse;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.ProviderStreamChunk;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import lombok.extern.slf4j.Slf4j;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.List;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 结果聚合阶段 - Pipeline 的核心阶段。
 *
 * <p>负责：
 * <ul>
 *   <li>准备 LLM 调用所需的所有参数（模型选择、提示词组合、工具暴露）</li>
 *   <li>调用 LLM Provider 进行同步或流式推理</li>
 *   <li>解析模型输出中的记忆决策标记</li>
 *   <li>记录调用追踪和 Token 使用统计</li>
 * </ul>
 *
 * <p>该阶段是 Pipeline 的"终点"，所有前期准备工作（上下文加载、模型解析、附件准备）
 * 都是为了最终调用 LLM 生成回复。</p>
 */
@Slf4j
public class ResultAggregationStage implements PipelineStage {

    /** 运行时模型注册表，用于根据 providerId 获取对应的 LLM Provider。 */
    private final ModelProviderRegistry providerRegistry;

    /** 记忆决策解析器，用于从模型输出中提取记忆控制指令并移除隐藏标记。 */
    private final MemoryDecisionParser memoryDecisionParser = new MemoryDecisionParser(null);

    /** 统一工具注册表；普通聊天直接暴露当前所有可用工具。 */
    private final ToolRouter toolRouter;

    /** 请求工厂，负责将管线上下文组装成完整的 ProviderRequest。 */
    private final ProviderRequestFactory providerRequestFactory;

    /**
     * 构造函数。
     *
     * @param providerRegistry 模型提供者注册表
     * @param toolRouter       工具路由器
     * @param promptBudget     提示词预算配置
     */
    public ResultAggregationStage(ModelProviderRegistry providerRegistry, ToolRouter toolRouter,
                                  PromptBudget promptBudget) {
        this.providerRegistry = providerRegistry;
        this.toolRouter = toolRouter;
        this.providerRequestFactory = new ProviderRequestFactory(new PromptComposer(promptBudget));
    }

    /**
     * 返回阶段顺序值。
     * <p>Order=40，在 ContextEnrichStage(10)、ModelResolutionStage(20)、
     * MultimodalGuardStage(30)、AttachmentPreparationStage(35) 之后执行。</p>
     */
    @Override
    public int order() { return 40; }

    /**
     * 同步处理管线上下文，生成最终回复。
     *
     * <p>执行流程：
     * 1. 准备调用参数（模型、Provider、请求对象）
     * 2. 创建追踪 Span
     * 3. 调用 LLM Provider
     * 4. 解析响应（提取记忆决策、设置最终回复）
     * 5. 记录 Token 使用统计
     *
     * @param ctx 管线上下文
     * @return 更新后的上下文（包含最终回复）
     */
    @Override
    public PipelineContext process(PipelineContext ctx) {
        try {
            // 1. 准备调用参数
            Invocation invocation = prepareInvocation(ctx);
            if (invocation == null) {
                return ctx;  // 准备失败，直接返回
            }

            // 2. 创建追踪 Span
            Span span = modelSpan("echomind.llm.invoke", invocation.model(), ctx, invocation.tools());
            try (Scope ignored = span.makeCurrent()) {
                // 3. 调用 LLM Provider（同步方式）
                ProviderResponse response = invocation.provider().chatWithUsage(invocation.request());

                // 4. 处理响应结果
                if (!response.modelInvoked()) {
                    ctx.getAttributes().put(PipelineContext.ATTR_MODEL_USAGE_NOT_APPLICABLE, true);
                }

                if (response.failed()) {
                    ctx.markFailed(response.failureReason());
                } else {
                    // 解析记忆决策并移除隐藏标记
                    MemoryDecisionParser.Parsed parsed = memoryDecisionParser.parseAndStrip(response.content());
                    ctx.setFinalResponse(parsed.content());
                    ctx.setMemoryDecision(parsed.decision());
                }

                // 5. 记录 Token 使用
                ctx.setTokenUsage(response.usage());
                tagUsage(span, response.usage());
            } catch (RuntimeException e) {
                EchoMindTrace.recordException(span, e);
                throw e;
            } finally {
                span.end();
            }
        } catch (Exception e) {
            log.error("LLM call failed in aggregation stage", e);
            ctx.markFailed("LLM call failed: " + e.getMessage());
        }
        return ctx;
    }

    /**
     * 流式生成文本片段，供 SSE 接口逐段推送。
     *
     * <p>执行流程：
     * 1. 准备调用参数
     * 2. 创建追踪 Span
     * 3. 调用 LLM Provider（流式方式）
     * 4. 逐段处理响应，过滤隐藏的记忆决策标记
     * 5. 推送可见内容到客户端
     *
     * @param ctx 管线上下文
     * @return 文本片段流
     */
    public Flux<String> streamResponse(PipelineContext ctx) {
        Invocation invocation = prepareInvocation(ctx);
        if (invocation == null) {
            return Flux.just(ctx.getFinalResponse());
        }

        Span span = modelSpan("echomind.llm.stream", invocation.model(), ctx, invocation.tools());
        Context otelContext = Context.current().with(span);
        MemoryDecisionParser.StreamFilter streamFilter = memoryDecisionParser.streamFilter();

        return Flux.defer(() -> {
            Scope scope = otelContext.makeCurrent();
            return invocation.provider().streamWithUsage(invocation.request())
            // 处理每个流片段
            .doOnNext(chunk -> {
                if (!chunk.modelInvoked()) {
                    ctx.getAttributes().put(PipelineContext.ATTR_MODEL_USAGE_NOT_APPLICABLE, true);
                }
                if (chunk.failed()) {
                    ctx.markFailed(chunk.failureReason());
                }
                if (chunk.hasUsage()) {
                    ctx.setTokenUsage(chunk.usage());
                    tagUsage(span, chunk.usage());
                }
            })
            // 失败时停止流
            .takeUntil(ProviderStreamChunk::failed)
            .filter(chunk -> !chunk.failed())
            .filter(ProviderStreamChunk::hasContent)
            .map(ProviderStreamChunk::content)
            // 过滤隐藏的记忆决策标记，只输出可见内容
            .<String>handle((chunk, sink) -> {
                String visible = streamFilter.accept(chunk);
                if (visible != null && !visible.isEmpty()) {
                    sink.next(visible);
                }
            })
            // 处理流结束后的收尾工作
            .concatWith(Mono.fromSupplier(() -> {
                    ctx.setMemoryDecision(streamFilter.decision());
                    return streamFilter.finish();
                })
                .filter(chunk -> chunk != null && !chunk.isEmpty()))
            .doOnError(error -> EchoMindTrace.recordException(span, error))
            .doFinally(signalType -> {
                try {
                    span.end();
                } finally {
                    scope.close();
                }
            });
        });
    }

    /**
     * 准备 LLM 调用所需的所有参数。
     *
     * <p>执行步骤：
     * 1. 获取解析后的模型规格
     * 2. 根据 providerId 获取对应的 ModelProvider
     * 3. 直接读取当前所有可用工具
     * 4. 使用 ProviderRequestFactory 构造完整的请求对象
     *
     * @param ctx 管线上下文
     * @return Invocation 对象（包含模型、Provider、请求、工具列表），准备失败返回 null
     */
    private Invocation prepareInvocation(PipelineContext ctx) {
        // 1. 获取解析后的模型规格
        ModelSpec model = ctx.getResolvedModel();
        if (model == null) {
            ctx.markFailed("No model resolved");
            return null;
        }

        // 2. 获取对应的 ModelProvider
        ModelProvider provider = providerRegistry.getProvider(model.providerId()).orElse(null);
        if (provider == null) {
            ctx.markFailed("No model provider available");
            return null;
        }

        // 3. 普通聊天不做候选筛选；内部控制面调用仍可显式关闭工具暴露。
        List<Tool> tools = exposedTools(ctx);

        // 4. 构造完整的请求对象
        ProviderRequest request = providerRequestFactory.create(
            ctx,
            model,
            memoryDecisionParser.appendInstruction(appendToolUsageInstruction(ctx.getSystemPrompt(), tools)),
            tools
        );
        // 模型 提供商 请求 工具
        return new Invocation(model, provider, request, tools);
    }

    private List<Tool> exposedTools(PipelineContext ctx) {
        if (Boolean.TRUE.equals(ctx.getAttributes().get(PipelineContext.ATTR_TOOL_EXPOSURE_DISABLED))) {
            log.debug("Tool exposure disabled for internal control-plane invocation");
            return List.of();
        }
        return List.copyOf(toolRouter.listAll());
    }

    /**
     * 给模型补充工具使用策略。
     *
     * <p>工具选择交给 LLM 后，系统提示需要清楚说明：遇到实时事实、不确定事实和日期计算时，
     * 应主动使用对应工具，而不是等用户把“搜索/查日期”说得很死。</p>
     */
    private String appendToolUsageInstruction(String systemPrompt, List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return systemPrompt;
        }

        boolean hasDateTool = hasFunctionTool(tools, "date_query");
        boolean hasSearchTool = hasFunctionTool(tools, "open_web_search");
        StringBuilder instruction = new StringBuilder();
        instruction.append("\n\n=== 工具使用规则 ===\n");
        instruction.append("本轮普通聊天已暴露所有可用工具。你必须根据工具描述和参数 schema 自主判断是否调用工具，不要因为 Agent 未绑定某个 Skill 就放弃可用工具。\n");
        instruction.append("只能调用本轮函数列表中真实存在的工具名，不要自造、缩写或改写工具名。\n");
        if (hasDateTool) {
            instruction.append("遇到当前日期、当前时间、今天、明天、后天、昨天、星期几、时区或相对日期计算，先调用 date_query，避免凭模型记忆猜日期。\n");
        }
        if (hasSearchTool) {
            instruction.append("遇到最新信息、新闻、人物近况、政策、院校招生、版本变更、公开网页事实核验，或你不确定答案是否过期时，先调用 open_web_search；不要等用户明确说“搜索”才搜索。\n");
        }
        instruction.append("如果工具结果与你的记忆冲突，以工具结果为准，并在回答中说明依据的时间或来源。\n");
        return (systemPrompt == null ? "" : systemPrompt) + instruction;
    }

    private boolean hasFunctionTool(List<Tool> tools, String functionName) {
        return tools.stream()
            .anyMatch(tool -> functionName.equals(ToolFunctionName.from(tool)));
    }

    /**
     * 创建 LLM 调用的追踪 Span。
     *
     * @param name   Span 名称
     * @param model  模型规格
     * @param ctx    管线上下文
     * @param tools  工具列表
     * @return 创建的 Span
     */
    private Span modelSpan(String name, ModelSpec model, PipelineContext ctx, List<Tool> tools) {
        Span span = EchoMindTrace.startSpan(name);
        span.setAttribute("echomind.provider_id", model.providerId());
        span.setAttribute("echomind.model_name", model.modelName());
        span.setAttribute("echomind.user_id", safe(ctx.getUserId()));
        span.setAttribute("echomind.agent_id", safe(ctx.getAgentId()));
        span.setAttribute("echomind.session_id", safe(ctx.getSessionId()));
        span.setAttribute("echomind.tool_count", tools == null ? 0 : tools.size());
        span.setAttribute("echomind.attachment_count", ctx.attachmentsForModel().size());
        return span;
    }

    /**
     * 向追踪 Span 添加 Token 使用标签。
     *
     * @param span  追踪 Span
     * @param usage Token 使用统计
     */
    private void tagUsage(Span span, TokenUsage usage) {
        if (usage == null) {
            return;
        }
        span.setAttribute("echomind.prompt_tokens", usage.promptTokens());
        span.setAttribute("echomind.completion_tokens", usage.completionTokens());
        span.setAttribute("echomind.total_tokens", usage.totalTokens());
        span.setAttribute("echomind.usage_source", "PROVIDER");
    }

    /**
     * 安全地处理可能为 null 的字符串。
     *
     * @param value 输入字符串
     * @return 非空字符串（null 转换为空字符串）
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * LLM 调用参数封装。
     *
     * @param model    模型规格
     * @param provider 模型提供者
     * @param request  请求对象
     * @param tools    工具列表
     */
    private record Invocation(ModelSpec model, ModelProvider provider, ProviderRequest request, List<Tool> tools) {}
}
