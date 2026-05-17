package com.echomind.usermemory.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.llm.provider.ModelProvider;
import com.echomind.llm.provider.ProviderRequest;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import com.echomind.memory.usermemory.UserMemoryCategory;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.usermemory.config.UserMemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryBatchAnalyzerTest {

    @Test
    void parsesBatchJsonAndKeepsOnlyKnownFactIdsForUpdateAndDelete() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.providerId()).thenReturn("mock");
        when(provider.chat(any(ProviderRequest.class))).thenReturn("""
            {
              "factsToAdd": [
                {"type":"preference","content":"用户希望注释使用中文","evidence":"用户说中文注释","confidence":0.9}
              ],
              "factsToUpdate": [
                {"factId":"known","type":"background","content":"用户项目是 EchoMind","evidence":"用户提到项目","confidence":0.8},
                {"factId":"unknown","content":"不应该采纳","confidence":0.8}
              ],
              "factsToDelete": [
                {"factId":"known","reason":"被覆盖"},
                {"factId":"unknown","reason":"无效"}
              ],
              "profileSnapshot": "用户使用 EchoMind，偏好中文注释。"
            }
            """);
        UserMemoryBatchAnalyzer analyzer = analyzer(provider);

        UserMemoryBatchResult result = analyzer.analyze(
            "user:default",
            "旧画像",
            List.of(turn("default", "session-1", AgentMessage.user("以后注释用中文"))),
            List.of(new UserMemoryHit("known", UserMemoryCategory.PREFERENCE, "旧事实", "", 0.8, 0.9))
        );

        assertThat(result.factsToAdd()).hasSize(1);
        assertThat(result.factsToAdd().get(0).category()).isEqualTo(UserMemoryCategory.PREFERENCE);
        assertThat(result.factsToUpdate()).extracting(UserMemoryBatchResult.FactToUpdate::factId)
            .containsExactly("known");
        assertThat(result.factsToDelete()).extracting(UserMemoryBatchResult.FactToDelete::factId)
            .containsExactly("known");
        assertThat(result.profileSnapshot()).contains("中文注释");
    }

    @Test
    void sendsOldProfileAndConversationToLlm() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.providerId()).thenReturn("mock");
        when(provider.chat(any(ProviderRequest.class))).thenReturn("{\"profileSnapshot\":\"旧画像\"}");
        UserMemoryBatchAnalyzer analyzer = analyzer(provider);

        analyzer.analyze(
            "user:default",
            "旧画像",
            List.of(turn("default", "session-1", AgentMessage.user("我用 Windows"))),
            List.of(new UserMemoryHit("fact-1", UserMemoryCategory.BACKGROUND, "用户使用 PowerShell", "", 0.9, 0.8))
        );

        var captor = forClass(ProviderRequest.class);
        verify(provider).chat(captor.capture());
        assertThat(captor.getValue().userMessage()).contains("旧画像", "我用 Windows", "factId=fact-1");
    }

    private UserMemoryBatchAnalyzer analyzer(ModelProvider provider) {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        registry.registerProvider(provider, List.of(
            new ModelSpec("mock", "mock-model", Set.of(ModelCapability.TEXT), true)
        ));
        return new UserMemoryBatchAnalyzer(
            new DynamicModelRouter(registry),
            registry,
            new ObjectMapper(),
            new UserMemoryProperties()
        );
    }

    private UserMemoryBatchTurn turn(String userId, String sessionId, AgentMessage message) {
        return new UserMemoryBatchTurn(userId, sessionId, "agent-1", Instant.now(), List.of(message));
    }
}
