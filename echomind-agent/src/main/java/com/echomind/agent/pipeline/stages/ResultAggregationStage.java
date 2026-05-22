package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.agent.pipeline.MemorySignalParser;
import com.echomind.agent.pipeline.PromptBudget;
import com.echomind.agent.tool.Tool;
import com.echomind.agent.tool.ToolRouter;
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
import io.opentelemetry.context.Scope;

import java.util.List;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 生成最终回复。
 *
 * <p>该阶段保留同步和流式 provider 调用控制流；Prompt 组合、工具暴露和
 * ProviderRequest 构造委托给同包 helper。</p>
 */
@Slf4j
public class ResultAggregationStage implements PipelineStage {

    // 这个是模型注册表,从这里面获取模型实例
    private final ModelProviderRegistry providerRegistry;
    //
    private final MemorySignalParser memorySignalParser = new MemorySignalParser(null);
    private final ToolExposurePlanner toolExposurePlanner;
    private final ProviderRequestFactory providerRequestFactory;

    public ResultAggregationStage(ModelProviderRegistry providerRegistry, ToolRouter toolRouter,
                                  PromptBudget promptBudget) {
        this.providerRegistry = providerRegistry;
        this.toolExposurePlanner = new ToolExposurePlanner(toolRouter);
        this.providerRequestFactory = new ProviderRequestFactory(
            new PromptComposer(promptBudget),
            toolExposurePlanner
        );
    }

    @Override
    public int order() { return 40; }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        try {
            Invocation invocation = prepareInvocation(ctx);
            if (invocation == null) {
                return ctx;
            }
            Span span = modelSpan("echomind.llm.invoke", invocation.model(), ctx, invocation.tools());
            try (Scope ignored = span.makeCurrent()) {
                ProviderResponse response = invocation.provider().chatWithUsage(invocation.request());
                if (!response.modelInvoked()) {
                    ctx.getAttributes().put("modelUsageNotApplicable", true);
                }
                if (response.failed()) {
                    ctx.markFailed(response.failureReason());
                } else {
                    MemorySignalParser.Parsed parsed = memorySignalParser.parseAndStrip(response.content());
                    ctx.setFinalResponse(parsed.content());
                    ctx.setMemorySignal(parsed.signal());
                }
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

    /** 流式生成文本片段，供 SSE 接口逐段推送。 */
    public Flux<String> streamResponse(PipelineContext ctx) {
        Invocation invocation = prepareInvocation(ctx);
        if (invocation == null) {
            return Flux.just(ctx.getFinalResponse());
        }
        Span span = modelSpan("echomind.llm.stream", invocation.model(), ctx, invocation.tools());
        MemorySignalParser.StreamFilter streamFilter = memorySignalParser.streamFilter();
        return invocation.provider().streamWithUsage(invocation.request())
            .doOnNext(chunk -> {
                if (!chunk.modelInvoked()) {
                    ctx.getAttributes().put("modelUsageNotApplicable", true);
                }
                if (chunk.failed()) {
                    ctx.markFailed(chunk.failureReason());
                }
                if (chunk.hasUsage()) {
                    ctx.setTokenUsage(chunk.usage());
                    tagUsage(span, chunk.usage());
                }
            })
            .filter(ProviderStreamChunk::hasContent)
            .map(ProviderStreamChunk::content)
            .<String>handle((chunk, sink) -> {
                String visible = streamFilter.accept(chunk);
                if (visible != null && !visible.isEmpty()) {
                    sink.next(visible);
                }
            })
            .concatWith(Mono.fromSupplier(() -> {
                    ctx.setMemorySignal(streamFilter.signal());
                    return streamFilter.finish();
                })
                .filter(chunk -> chunk != null && !chunk.isEmpty()))
            .doOnError(error -> EchoMindTrace.recordException(span, error))
            .doFinally(signalType -> {
                span.end();
            });
    }

    private Invocation prepareInvocation(PipelineContext ctx) {
        ModelSpec model = ctx.getResolvedModel();
        if (model == null) {
            ctx.markFailed("No model resolved");
            return null;
        }
        ModelProvider provider = providerRegistry.getProvider(model.providerId()).orElse(null);
        if (provider == null) {
            ctx.markFailed("No model provider available");
            return null;
        }
        ToolExposure exposure = toolExposurePlanner.plan(ctx);
        ProviderRequest request = providerRequestFactory.create(
            ctx,
            model,
            memorySignalParser.appendInstruction(ctx.getSystemPrompt()),
            exposure
        );
        return new Invocation(model, provider, request, exposure.tools());
    }

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

    private void tagUsage(Span span, TokenUsage usage) {
        if (usage == null) {
            return;
        }
        span.setAttribute("echomind.prompt_tokens", usage.promptTokens());
        span.setAttribute("echomind.completion_tokens", usage.completionTokens());
        span.setAttribute("echomind.total_tokens", usage.totalTokens());
        span.setAttribute("echomind.usage_source", "PROVIDER");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record Invocation(ModelSpec model, ModelProvider provider, ProviderRequest request, List<Tool> tools) {}
}
