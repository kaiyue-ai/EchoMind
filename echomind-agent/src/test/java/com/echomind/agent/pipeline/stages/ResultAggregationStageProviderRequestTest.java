package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PromptBudget;
import com.echomind.agent.tool.Tool;
import com.echomind.agent.tool.ToolRouter;
import com.echomind.agent.tool.ToolResult;
import com.echomind.common.model.MessageAttachment;
import com.echomind.llm.provider.ProviderRequest;
import com.echomind.llm.router.DynamicModelRouter;
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
    void processPassesAttachmentsAndToolsToNativeProvider() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        registry.registerProvider(new CapturingProvider("mock", captured), List.of(
            new ModelSpec("mock", "vision-tool-model", Set.of(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.FUNCTION), true)
        ));
        DynamicModelRouter router = new DynamicModelRouter(registry);
        ToolRouter toolRouter = new ToolRouter();
        toolRouter.register(new StubTool());
        ResultAggregationStage stage = new ResultAggregationStage(
            router,
            registry,
            toolRouter,
            new PromptBudget(4000, 2000, 800)
        );

        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:vision-tool-model");
        ctx.setUserMessage("帮我看图并搜索这张图里是什么");
        ctx.getAttachments().add(MessageAttachment.image(
            "oss://bucket/chat-images/test.png",
            "https://example.com/test.png",
            "image/png",
            "test.png",
            123L
        ));
        ctx.getAttributes().put("agentSkillIds", List.of("web-search"));

        stage.process(ctx);

        assertThat(ctx.getFinalResponse()).isEqualTo("ok");
        ProviderRequest request = captured.get();
        assertThat(request).isNotNull();
        assertThat(request.attachments()).hasSize(1);
        assertThat(request.tools()).hasSize(1);
        assertThat(request.tools().get(0).name()).isEqualTo("web_search");
    }

    private record CapturingProvider(String providerId, AtomicReference<ProviderRequest> captured)
        implements com.echomind.llm.provider.ModelProvider {

        @Override
        public boolean supports(ModelSpec model) {
            return providerId.equals(model.providerId());
        }

        @Override
        public String chat(ProviderRequest request) {
            captured.set(request);
            return "ok";
        }

        @Override
        public Flux<String> stream(ProviderRequest request) {
            captured.set(request);
            return Flux.just("ok");
        }
    }

    private static class StubTool implements Tool {

        @Override
        public String name() {
            return "web-search";
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
            return "skill";
        }

        @Override
        public String sourceId() {
            return "web-search@1.0.0";
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok", 1));
        }
    }
}
