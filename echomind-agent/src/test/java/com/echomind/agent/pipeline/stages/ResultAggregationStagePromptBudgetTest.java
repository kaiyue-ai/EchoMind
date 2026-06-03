package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.composing.PromptBudget;
import com.echomind.agent.pipeline.composing.PromptComposer;
import com.echomind.common.model.AgentMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultAggregationStagePromptBudgetTest {

    @Test
    void buildPromptKeepsFinalPromptInsideBudget() {
        PromptComposer composer = new PromptComposer(new PromptBudget(1200, 700, 300));
        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("当前问题必须保留");
        ctx.getMessages().add(AgentMessage.system("知识库：" + "K".repeat(2000)));
        ctx.getMessages().add(AgentMessage.user("旧问题：" + "U".repeat(1000)));
        ctx.getMessages().add(AgentMessage.assistant("旧回答：" + "A".repeat(1000)));
        ctx.getMessages().add(AgentMessage.user(ctx.getUserMessage()));

        String prompt = composer.compose(ctx);

        assertThat(prompt).hasSizeLessThanOrEqualTo(1200);
        assertThat(prompt).contains("当前问题必须保留");
        assertThat(prompt).contains("=== 参考上下文 ===");
        assertThat(prompt).contains("=== 短期对话历史 ===");
        assertThat(prompt).contains("=== 当前用户问题（最高优先级） ===");
        assertThat(prompt).doesNotContain("=== Conversation History ===");
    }

    @Test
    void currentUserMessageWinsWhenItConsumesWholeBudget() {
        PromptComposer composer = new PromptComposer(new PromptBudget(1000, 700, 300));
        PipelineContext ctx = new PipelineContext();
        ctx.setUserMessage("Q".repeat(2000));
        ctx.getMessages().add(AgentMessage.system("知识库：" + "K".repeat(2000)));
        ctx.getMessages().add(AgentMessage.user(ctx.getUserMessage()));

        String prompt = composer.compose(ctx);

        assertThat(prompt).hasSizeLessThanOrEqualTo(1000);
        assertThat(prompt).startsWith("=== 当前用户问题（最高优先级） ===");
        assertThat(prompt).contains("必须优先回答当前用户问题");
        assertThat(prompt).endsWith("...");
        assertThat(prompt).doesNotContain("Conversation History");
    }
}
