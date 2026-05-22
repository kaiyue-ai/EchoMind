package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.MessageAttachment;
import com.echomind.llm.provider.dto.ProviderResponse;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.ProviderStreamChunk;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultimodalGuardStageTest {

    @Test
    void imageMessageRequiresVisionCapability() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        registry.registerProvider(new StubProvider("mock"), List.of(
            new ModelSpec("mock", "text-model", Set.of(ModelCapability.TEXT), true)
        ));
        PipelineContext ctx = new PipelineContext();
        ctx.setModelId("mock:text-model");
        ctx.getAttachments().add(MessageAttachment.image(
            "local://chat-images/a.png",
            "/api/storage/objects/chat-images/a.png",
            "image/png",
            "a.png",
            128L
        ));

        assertThatThrownBy(() -> new MultimodalGuardStage(registry).process(ctx))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持多模态");
    }

    @Test
    void visionModelAcceptsImageMessage() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        registry.registerProvider(new StubProvider("mock"), List.of(
            new ModelSpec("mock", "vision-model", Set.of(ModelCapability.TEXT, ModelCapability.VISION), true)
        ));
        PipelineContext ctx = new PipelineContext();
        ctx.setModelId("mock:vision-model");
        ctx.getAttachments().add(MessageAttachment.image(
            "local://chat-images/a.png",
            "/api/storage/objects/chat-images/a.png",
            "image/png",
            "a.png",
            128L
        ));

        assertThatCode(() -> new MultimodalGuardStage(registry).process(ctx))
            .doesNotThrowAnyException();
    }

    private record StubProvider(String providerId) implements com.echomind.llm.provider.ModelProvider {
        @Override
        public boolean supports(ModelSpec model) {
            return providerId.equals(model.providerId());
        }

        @Override
        public ProviderResponse chatWithUsage(ProviderRequest request) {
            return ProviderResponse.text("ok");
        }

        @Override
        public reactor.core.publisher.Flux<ProviderStreamChunk> streamWithUsage(ProviderRequest request) {
            return reactor.core.publisher.Flux.just(ProviderStreamChunk.text("ok"));
        }
    }
}
