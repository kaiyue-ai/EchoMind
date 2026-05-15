package com.echomind.usermemory.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.llm.provider.ModelProvider;
import com.echomind.llm.provider.ProviderRequest;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import com.echomind.usermemory.config.UserMemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserProfileExtractorTest {

    @Test
    void parsesJsonArrayResponse() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.providerId()).thenReturn("mock");
        when(provider.chat(any(ProviderRequest.class))).thenReturn("""
            [{"category":"preference","content":"用户喜欢简洁代码","evidence":"用户说喜欢简洁代码","confidence":1.5}]
            """);
        UserProfileExtractor extractor = extractor(provider);

        List<ExtractedUserMemory> result = extractor.extract(
            "session-1",
            List.of(),
            List.of(AgentMessage.user("我喜欢简洁代码"))
        );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("用户喜欢简洁代码");
        assertThat(result.get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void returnsEmptyListForInvalidJson() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.providerId()).thenReturn("mock");
        when(provider.chat(any(ProviderRequest.class))).thenReturn("not json");
        UserProfileExtractor extractor = extractor(provider);

        List<ExtractedUserMemory> result = extractor.extract(
            "session-1",
            List.of(),
            List.of(AgentMessage.user("hello"))
        );

        assertThat(result).isEmpty();
    }

    private UserProfileExtractor extractor(ModelProvider provider) {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        registry.registerProvider(provider, List.of(
            new ModelSpec("mock", "mock-model", Set.of(ModelCapability.TEXT), true)
        ));
        return new UserProfileExtractor(
            new DynamicModelRouter(registry),
            registry,
            new ObjectMapper(),
            new UserMemoryProperties()
        );
    }
}
