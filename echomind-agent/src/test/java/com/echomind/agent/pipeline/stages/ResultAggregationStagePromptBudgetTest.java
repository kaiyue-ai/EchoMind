package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PromptBudget;
import com.echomind.common.model.AgentMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ResultAggregationStagePromptBudgetTest {

    @Test
    void buildPromptKeepsFinalPromptInsideBudget() throws Exception {
        ResultAggregationStage stage = new ResultAggregationStage(
            null,
            null,
            null,
            new PromptBudget(1200, 700, 300)
        );
        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("当前问题必须保留");
        ctx.getMessages().add(AgentMessage.system("知识库：" + "K".repeat(2000)));
        ctx.getMessages().add(AgentMessage.user("旧问题：" + "U".repeat(1000)));
        ctx.getMessages().add(AgentMessage.assistant("旧回答：" + "A".repeat(1000)));
        ctx.getMessages().add(AgentMessage.user(ctx.getUserMessage()));

        String prompt = invokeBuildPrompt(stage, ctx);

        assertThat(prompt).hasSizeLessThanOrEqualTo(1200);
        assertThat(prompt).contains("当前问题必须保留");
        assertThat(prompt).contains("=== Conversation History ===");
    }

    @Test
    void currentUserMessageWinsWhenItConsumesWholeBudget() throws Exception {
        ResultAggregationStage stage = new ResultAggregationStage(
            null,
            null,
            null,
            new PromptBudget(1000, 700, 300)
        );
        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("Q".repeat(2000));
        ctx.getMessages().add(AgentMessage.system("知识库：" + "K".repeat(2000)));
        ctx.getMessages().add(AgentMessage.user(ctx.getUserMessage()));

        String prompt = invokeBuildPrompt(stage, ctx);

        assertThat(prompt).hasSizeLessThanOrEqualTo(1000);
        assertThat(prompt).startsWith("Q");
        assertThat(prompt).endsWith("...");
        assertThat(prompt).doesNotContain("Conversation History");
    }

    private String invokeBuildPrompt(ResultAggregationStage stage, PipelineContext ctx) throws Exception {
        Method method = ResultAggregationStage.class.getDeclaredMethod("buildPrompt", PipelineContext.class);
        method.setAccessible(true);
        return (String) method.invoke(stage, ctx);
    }
}
