package com.echomind.llm.provider;

import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MockModelProviderTest {

    private final MockModelProvider provider = new MockModelProvider();
    private final ModelSpec model = new ModelSpec(
        "mock",
        "mock-model",
        Set.of(ModelCapability.TEXT, ModelCapability.FUNCTION),
        true
    );

    @Test
    void returnsStructuredPlannerResponseForTeamPrompt() {
        String response = provider.chatWithUsage(request("""
            You are the Planner in an Agent Team.
            User task:
            smoke test
            """)).content();

        assertThat(response)
            .contains("\"taskLevel\":\"COMPLEX\"")
            .contains("\"clientStepId\":\"step-1\"")
            .contains("\"dependsOn\":[\"step-1\"]");
    }

    @Test
    void returnsFirstExecutorCandidateForTeamAgentSelectorPrompt() {
        String response = provider.chatWithUsage(request("""
            你是 Agent Team 的 AgentSelector
            请按 JSON schema 返回：
            {"memberKey":"候选 memberKey","agentId":"候选 agentId","reason":"选择原因"}
            Executor 候选：
            [{"memberKey":"agent-a#20","agentId":"agent-a"}]
            """)).content();

        assertThat(response)
            .contains("\"memberKey\":\"agent-a#20\"")
            .contains("\"agentId\":\"agent-a\"");
    }

    @Test
    void returnsSuccessfulGlobalReviewForTeamPrompt() {
        String response = provider.chatWithUsage(request("You are the GlobalReviewer")).content();

        assertThat(response)
            .contains("\"action\":\"SUCCESS\"")
            .contains("Mock 最终报告");
    }

    private ProviderRequest request(String userMessage) {
        return new ProviderRequest(model, "", userMessage, List.of(), List.of());
    }
}
