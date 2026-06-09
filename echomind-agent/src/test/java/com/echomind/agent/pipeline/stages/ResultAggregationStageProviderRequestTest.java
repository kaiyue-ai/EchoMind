package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.composing.PromptBudget;
import com.echomind.agent.pipeline.planning.MemoryDecisionParser;
import com.echomind.agent.tool.core.Tool;
import com.echomind.agent.tool.core.ToolResult;
import com.echomind.agent.tool.router.ToolRouter;
import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MessageAttachment;
import com.echomind.common.model.TokenUsage;
import com.echomind.llm.provider.dto.ProviderResponse;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.ProviderStreamChunk;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ResultAggregationStageProviderRequestTest {

    @Test
    void processSeparatesReferenceContextHistoryAndCurrentQuestionInPrompt() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        ModelSpec model = model("mock", "prompt-model", ModelCapability.TEXT);
        registry.registerProvider(new CapturingProvider("mock", captured), List.of(model));
        ResultAggregationStage stage = stage(registry, new ToolRouter());

        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:prompt-model");
        ctx.setResolvedModel(model);
        ctx.setUserMessage("当前真正问题：只回答这个。");
        ctx.getMessages().add(AgentMessage.system("用户画像：用户偏好中文注释"));
        ctx.getMessages().add(AgentMessage.user("上一轮用户问题"));
        ctx.getMessages().add(AgentMessage.tool("tool-1", "旧工具结果"));
        ctx.getMessages().add(AgentMessage.assistant("上一轮回复"));
        ctx.getMessages().add(AgentMessage.user("当前真正问题：只回答这个。"));

        stage.process(ctx);

        String prompt = captured.get().userMessage();
        assertThat(prompt).contains("=== 参考上下文 ===");
        assertThat(prompt).contains("=== 短期对话历史 ===");
        assertThat(prompt).contains("=== 当前用户问题（最高优先级） ===");
        assertThat(prompt).contains("必须优先回答当前用户问题");
        assertThat(prompt).doesNotContain("=== Conversation History ===");
        assertThat(prompt.indexOf("=== 参考上下文 ==="))
            .isLessThan(prompt.indexOf("=== 短期对话历史 ==="));
        assertThat(prompt.indexOf("=== 短期对话历史 ==="))
            .isLessThan(prompt.indexOf("=== 当前用户问题（最高优先级） ==="));
        assertThat(prompt.indexOf("用户画像：用户偏好中文注释"))
            .isBetween(prompt.indexOf("=== 参考上下文 ==="), prompt.indexOf("=== 短期对话历史 ==="));
        assertThat(prompt.indexOf("上一轮用户问题"))
            .isBetween(prompt.indexOf("=== 短期对话历史 ==="), prompt.indexOf("=== 当前用户问题（最高优先级） ==="));
        assertThat(prompt.indexOf("旧工具结果"))
            .isBetween(prompt.indexOf("=== 短期对话历史 ==="), prompt.indexOf("=== 当前用户问题（最高优先级） ==="));
        assertThat(prompt.trim()).endsWith("当前真正问题：只回答这个。");
    }

    @Test
    void processPassesAttachmentsAndToolsToNativeProvider() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        ModelSpec model = model("mock", "vision-tool-model",
            ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.FUNCTION);
        registry.registerProvider(new CapturingProvider("mock", captured), List.of(model));
        ToolRouter toolRouter = new ToolRouter();
        toolRouter.register(new StubTool());
        ResultAggregationStage stage = stage(registry, toolRouter);

        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:vision-tool-model");
        ctx.setResolvedModel(model);
        ctx.setUserMessage("帮我看图并搜索这张图里是什么");
        ctx.getAttachments().add(MessageAttachment.image(
            "oss://bucket/chat-images/test.png",
            "https://example.com/test.png",
            "image/png",
            "test.png",
            123L
        ));
        stage.process(ctx);

        assertThat(ctx.getFinalResponse()).isEqualTo("ok");
        assertThat(ctx.getTokenUsage()).isNull();
        ProviderRequest request = captured.get();
        assertThat(request).isNotNull();
        assertThat(request.attachments()).hasSize(1);
        assertThat(request.tools()).hasSize(1);
        assertThat(request.tools().get(0).name()).isEqualTo("open_web_search");
        assertThat(request.systemPrompt()).contains("先调用 open_web_search");
        assertThat(request.requiredToolName()).isNull();
        assertThat(request.toolInputMessage()).isEqualTo("帮我看图并搜索这张图里是什么");
    }

    @Test
    void toolOutputIsNeverMarkedAsDirectResult() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        ModelSpec model = model("mock", "tool-model", ModelCapability.TEXT, ModelCapability.FUNCTION);
        registry.registerProvider(new CapturingProvider("mock", captured), List.of(model));
        ToolRouter toolRouter = new ToolRouter();
        toolRouter.register(new WeatherTool());
        ResultAggregationStage stage = stage(registry, toolRouter);

        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:tool-model");
        ctx.setResolvedModel(model);
        ctx.setUserMessage("重庆天气");

        stage.process(ctx);

        String toolOutput = captured.get().tools().get(0).call("{}");
        assertThat(captured.get().requiredToolName()).isNull();
        assertThat(toolOutput).isEqualTo("ok");
    }

    @Test
    void processUsesRawUserMessageForToolArgumentsWhenGovernanceMaskedPrompt() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        ModelSpec model = model("mock", "tool-model", ModelCapability.TEXT, ModelCapability.FUNCTION);
        registry.registerProvider(new CapturingProvider("mock", captured), List.of(model));
        ToolRouter toolRouter = new ToolRouter();
        toolRouter.register(new RailwayTool());
        ResultAggregationStage stage = stage(registry, toolRouter);

        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:tool-model");
        ctx.setResolvedModel(model);
        ctx.setUserMessage("查询[LOCATION]到上海的火车票");
        ctx.getAttributes().put(PipelineContext.ATTR_RAW_USER_MESSAGE, "查询重庆到上海的火车票");

        stage.process(ctx);

        ProviderRequest request = captured.get();
        assertThat(request.requiredToolName()).isNull();
        assertThat(request.userMessage()).contains("查询[LOCATION]到上海的火车票");
        assertThat(request.toolInputMessage()).isEqualTo("查询重庆到上海的火车票");
    }

    @Test
    void processExposesAllToolsForFollowUpCorrection() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        ModelSpec model = model("mock", "tool-model", ModelCapability.TEXT, ModelCapability.FUNCTION);
        registry.registerProvider(new CapturingProvider("mock", captured), List.of(model));
        ToolRouter toolRouter = new ToolRouter();
        toolRouter.register(new RailwayTool());
        toolRouter.register(new DateTool());
        toolRouter.register(new StubTool());
        ResultAggregationStage stage = stage(registry, toolRouter);

        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:tool-model");
        ctx.setResolvedModel(model);
        ctx.setUserMessage("高考两天???你确定吗???");
        ctx.getMessages().add(AgentMessage.user("搜搜今天的新闻"));
        ctx.getMessages().add(AgentMessage.assistant("已查询今日新闻。"));
        ctx.getMessages().add(AgentMessage.user("高考两天???你确定吗???"));

        stage.process(ctx);

        ProviderRequest request = captured.get();
        assertThat(request.tools()).extracting(tool -> tool.name())
            .containsExactlyInAnyOrder("tool_12306", "date_query", "open_web_search");
        assertThat(request.systemPrompt()).contains("先调用 date_query");
        assertThat(request.systemPrompt()).contains("先调用 open_web_search");
        assertThat(request.requiredToolName()).isNull();
    }

    @Test
    void processHidesToolsWhenExposureIsDisabledForInternalCalls() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        ModelSpec model = model("mock", "tool-model", ModelCapability.TEXT, ModelCapability.FUNCTION);
        registry.registerProvider(new CapturingProvider("mock", captured), List.of(model));
        ToolRouter toolRouter = new ToolRouter();
        toolRouter.register(new StubTool());
        ResultAggregationStage stage = stage(registry, toolRouter);

        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:tool-model");
        ctx.setResolvedModel(model);
        ctx.setUserMessage("只输出 JSON");
        ctx.getAttributes().put(PipelineContext.ATTR_TOOL_EXPOSURE_DISABLED, true);

        stage.process(ctx);

        assertThat(captured.get().tools()).isEmpty();
        assertThat(captured.get().systemPrompt()).doesNotContain("工具使用规则");
    }

    @Test
    void processStoresProviderUsageOnPipelineContext() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        ModelSpec model = model("mock", "usage-model", ModelCapability.TEXT);
        registry.registerProvider(new CapturingProvider("mock", captured, new TokenUsage(7, 3, 10)), List.of(model));
        ResultAggregationStage stage = stage(registry, new ToolRouter());

        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:usage-model");
        ctx.setResolvedModel(model);
        ctx.setUserMessage("你好");

        stage.process(ctx);

        assertThat(ctx.getFinalResponse()).isEqualTo("ok");
        assertThat(ctx.getTokenUsage()).isEqualTo(new TokenUsage(7, 3, 10));
    }

    @Test
    void processStripsHiddenMemoryDecisionAndStoresItOnContext() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        ModelSpec model = model("mock", "memory-decision-model", ModelCapability.TEXT);
        registry.registerProvider(new CapturingProvider(
            "mock",
            captured,
            null,
            "已记住。<ECHOMIND_MEMORY_DECISION>{\"rememberFacts\":true,\"refreshProfile\":true,\"reason\":\"用户偏好\"}</ECHOMIND_MEMORY_DECISION>"
        ), List.of(model));
        ResultAggregationStage stage = stage(registry, new ToolRouter());

        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:memory-decision-model");
        ctx.setResolvedModel(model);
        ctx.setUserMessage("以后注释用中文");

        stage.process(ctx);

        assertThat(captured.get().systemPrompt()).contains(MemoryDecisionParser.START_MARKER);
        assertThat(ctx.getFinalResponse()).isEqualTo("已记住。");
        assertThat(ctx.getMemoryDecision().rememberFacts()).isTrue();
        assertThat(ctx.getMemoryDecision().refreshProfile()).isTrue();
    }

    @Test
    void processStoresProviderFailureAsStructuredPipelineFailure() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        ModelSpec model = model("mock", "failure-model", ModelCapability.TEXT);
        registry.registerProvider(new CapturingProvider("mock", captured, null,
            ProviderResponse.failure("provider timeout")), List.of(
            model
        ));
        ResultAggregationStage stage = stage(registry, new ToolRouter());

        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:failure-model");
        ctx.setResolvedModel(model);
        ctx.setUserMessage("你好");

        stage.process(ctx);

        assertThat(ctx.hasFailed()).isTrue();
        assertThat(ctx.effectiveFailureReason()).isEqualTo("provider timeout");
        assertThat(ctx.getFinalResponse()).isEqualTo("[Error] provider timeout");
    }

    @Test
    void streamResponseBuffersSmallChunksWithoutNullMapperFailure() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        ModelSpec model = model("mock", "small-chunk-stream", ModelCapability.TEXT);
        registry.registerProvider(new SmallChunkStreamingProvider(), List.of(model));
        ResultAggregationStage stage = stage(registry, new ToolRouter());
        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:small-chunk-stream");
        ctx.setResolvedModel(model);
        ctx.setUserMessage("你好");

        List<String> chunks = stage.streamResponse(ctx).collectList().block();

        assertThat(chunks).containsExactly("你好");
        assertThat(ctx.hasFailed()).isFalse();
    }

    @Test
    void streamResponseMarksProviderFailureWithoutEmittingErrorAsToken() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        ModelSpec model = model("mock", "failing-stream", ModelCapability.TEXT);
        registry.registerProvider(new FailingStreamingProvider(), List.of(model));
        ResultAggregationStage stage = stage(registry, new ToolRouter());
        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:failing-stream");
        ctx.setResolvedModel(model);
        ctx.setUserMessage("你好");

        List<String> chunks = stage.streamResponse(ctx).collectList().block();

        assertThat(chunks).isEmpty();
        assertThat(ctx.hasFailed()).isTrue();
        assertThat(ctx.effectiveFailureReason()).isEqualTo("provider 400");
        assertThat(ctx.getFinalResponse()).isEqualTo("[Error] provider 400");
    }

    private ResultAggregationStage stage(ModelProviderRegistry registry, ToolRouter toolRouter) {
        return new ResultAggregationStage(registry, toolRouter, new PromptBudget(4000, 2000, 800));
    }

    private ModelSpec model(String providerId, String modelName, ModelCapability... capabilities) {
        return new ModelSpec(providerId, modelName, Set.of(capabilities), true);
    }

    private record CapturingProvider(
        String providerId,
        AtomicReference<ProviderRequest> captured,
        TokenUsage usage,
        String responseContent,
        ProviderResponse response
    )
        implements com.echomind.llm.provider.ModelProvider {

        private CapturingProvider(String providerId, AtomicReference<ProviderRequest> captured) {
            this(providerId, captured, null, "ok");
        }

        private CapturingProvider(String providerId, AtomicReference<ProviderRequest> captured, TokenUsage usage) {
            this(providerId, captured, usage, "ok");
        }

        private CapturingProvider(String providerId, AtomicReference<ProviderRequest> captured, TokenUsage usage,
                                  String responseContent) {
            this(providerId, captured, usage, responseContent, null);
        }

        private CapturingProvider(String providerId, AtomicReference<ProviderRequest> captured, TokenUsage usage,
                                  ProviderResponse response) {
            this(providerId, captured, usage, null, response);
        }

        @Override
        public boolean supports(ModelSpec model) {
            return providerId.equals(model.providerId());
        }

        @Override
        public ProviderResponse chatWithUsage(ProviderRequest request) {
            captured.set(request);
            if (response != null) {
                return response;
            }
            return new ProviderResponse(responseContent, usage);
        }

        @Override
        public Flux<ProviderStreamChunk> streamWithUsage(ProviderRequest request) {
            captured.set(request);
            return usage == null
                ? Flux.just(ProviderStreamChunk.text(responseContent))
                : Flux.just(ProviderStreamChunk.text(responseContent), ProviderStreamChunk.usage(usage));
        }
    }

    private static class SmallChunkStreamingProvider implements com.echomind.llm.provider.ModelProvider {

        @Override
        public String providerId() {
            return "mock";
        }

        @Override
        public boolean supports(ModelSpec model) {
            return "mock".equals(model.providerId());
        }

        @Override
        public ProviderResponse chatWithUsage(ProviderRequest request) {
            return new ProviderResponse("你好", null);
        }

        @Override
        public Flux<ProviderStreamChunk> streamWithUsage(ProviderRequest request) {
            return Flux.just(ProviderStreamChunk.text("你"), ProviderStreamChunk.text("好"));
        }
    }

    private static class FailingStreamingProvider implements com.echomind.llm.provider.ModelProvider {

        @Override
        public String providerId() {
            return "mock";
        }

        @Override
        public boolean supports(ModelSpec model) {
            return "mock".equals(model.providerId());
        }

        @Override
        public ProviderResponse chatWithUsage(ProviderRequest request) {
            return ProviderResponse.failure("provider 400");
        }

        @Override
        public Flux<ProviderStreamChunk> streamWithUsage(ProviderRequest request) {
            return Flux.just(ProviderStreamChunk.failure("provider 400"));
        }
    }

    private static class WeatherTool extends StubTool {

        @Override
        public String name() {
            return "weather-query";
        }

        @Override
        public List<String> tags() {
            return List.of("weather");
        }

        @Override
        public List<String> keywords() {
            return List.of("天气");
        }
    }

    private static class DateTool extends StubTool {

        @Override
        public String name() {
            return "date-query";
        }

        @Override
        public String description() {
            return "日期查询";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of("type", "object", "properties", Map.of(
                "offsetDays", Map.of("type", "integer")
            ));
        }

        @Override
        public List<String> tags() {
            return List.of("date", "time");
        }

        @Override
        public List<String> keywords() {
            return List.of("今天", "明天", "后天", "日期");
        }

        @Override
        public String sourceId() {
            return "date-query@1.0.0";
        }
    }

    private static class StubTool implements Tool {

        @Override
        public String name() {
            return "open_web_search";
        }

        @Override
        public String description() {
            return "Search the web";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query")
            );
        }

        @Override
        public List<String> tags() {
            return List.of("search", "web");
        }

        @Override
        public List<String> keywords() {
            return List.of("搜索", "看图", "是什么");
        }

        @Override
        public String sourceType() {
            return "mcp";
        }

        @Override
        public String sourceId() {
            return "mcp:open-websearch";
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok", 1));
        }
    }

    private static class RailwayTool implements Tool {

        @Override
        public String name() {
            return "12306";
        }

        @Override
        public String description() {
            return "12306 火车票查询";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of(
                    "from", Map.of("type", "string"),
                    "to", Map.of("type", "string"),
                    "date", Map.of("type", "string")
                )
            );
        }

        @Override
        public List<String> tags() {
            return List.of("12306", "railway", "ticket");
        }

        @Override
        public List<String> keywords() {
            return List.of("12306", "火车票", "高铁票", "余票");
        }

        @Override
        public String sourceType() {
            return "skill";
        }

        @Override
        public String sourceId() {
            return "12306@1.0.0";
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok", 1));
        }
    }
}
