package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.llm.provider.MockModelProvider;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModelResolutionStageTest {

    @Test
    void writesModelIdAndResolvedModelTogether() {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        ModelSpec model = new ModelSpec("mock", "fast-tool-model",
            Set.of(ModelCapability.TEXT, ModelCapability.FUNCTION), true);
        registry.registerProvider(new MockModelProvider(), List.of(model));
        ModelResolutionStage stage = new ModelResolutionStage(new DynamicModelRouter(registry));

        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setModelId("mock:fast-tool-model");

        stage.process(ctx);

        assertThat(ctx.getModelId()).isEqualTo("mock:fast-tool-model");
        assertThat(ctx.getResolvedModel()).isEqualTo(model);
    }
}
