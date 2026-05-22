package com.echomind.console.usage;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.TokenUsage;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.auth.UserAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCallUsageServiceTest {

    @Test
    void recordsProviderUsageWithoutEstimating() {
        AiCallUsageRepository repository = mock(AiCallUsageRepository.class);
        when(repository.save(any(AiCallUsageEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        when(userRepository.findById("user-a")).thenReturn(Optional.empty());
        AiCallUsageService service = new AiCallUsageService(repository, userRepository);
        PipelineContext ctx = new PipelineContext();
        ctx.setTraceId("trace-a");
        ctx.setUserId("user-a");
        ctx.setAgentId("default");
        ctx.setSessionId("session-a");
        ctx.setModelId("deepseek:deepseek-v4-flash");
        ctx.setFinalResponse("ok");
        ctx.setTokenUsage(new TokenUsage(12, 4, 16));

        AiCallUsageEntity usage = service.recordSuccess(
            "echomind.chat.sync",
            new AuthUser("user-a", "alice", true),
            ctx,
            System.nanoTime()
        );

        assertThat(usage.getUsageSource()).isEqualTo(TokenUsageSource.PROVIDER);
        assertThat(usage.getPromptTokens()).isEqualTo(12);
        assertThat(usage.getCompletionTokens()).isEqualTo(4);
        assertThat(usage.getTotalTokens()).isEqualTo(16);
        verify(repository).save(any(AiCallUsageEntity.class));
    }

    @Test
    void rejectsCallsWhenProviderUsageIsMissing() {
        AiCallUsageRepository repository = mock(AiCallUsageRepository.class);
        AiCallUsageService service = new AiCallUsageService(repository, mock(UserAccountRepository.class));
        PipelineContext ctx = new PipelineContext();
        ctx.setTraceId("trace-a");

        assertThatThrownBy(() -> service.recordSuccess(
            "echomind.chat.sync",
            new AuthUser("user-a", "alice", true),
            ctx,
            System.nanoTime()
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("模型未返回原生 token usage");
        verify(repository, never()).save(any());
    }
}
