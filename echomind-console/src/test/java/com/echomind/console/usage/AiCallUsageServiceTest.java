package com.echomind.console.usage;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.TokenUsage;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.auth.UserAccountMapper;
import com.echomind.console.quota.TokenQuotaExceededException;
import com.echomind.console.quota.TokenQuotaService;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCallUsageServiceTest {

    @Test
    void recordsProviderUsageWithoutEstimating() {
        AiCallUsageMapper mapper = mock(AiCallUsageMapper.class);
        when(mapper.upsertById(any(AiCallUsageEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserAccountMapper userMapper = mock(UserAccountMapper.class);
        when(userMapper.selectOptionalById("user-a")).thenReturn(Optional.empty());
        AiCallUsageService service = new AiCallUsageService(mapper, userMapper);
        PipelineContext ctx = new PipelineContext();
        ctx.setTraceId("trace-a");
        ctx.setUserId("user-a");
        ctx.setAgentId("default");
        ctx.setSessionId("session-a");
        ctx.setModelId("deepseek:deepseek-v4-flash");
        ctx.setFinalResponse("ok");
        ctx.setTokenUsage(new TokenUsage(12, 4, 16));

        AiCallUsageEntity usage = service.recordSuccess(
            "echomind.chat.stream.consume",
            new AuthUser("user-a", "alice", true),
            ctx,
            System.nanoTime()
        );

        assertThat(usage.getUsageSource()).isEqualTo(TokenUsageSource.PROVIDER);
        assertThat(usage.getPromptTokens()).isEqualTo(12);
        assertThat(usage.getCompletionTokens()).isEqualTo(4);
        assertThat(usage.getTotalTokens()).isEqualTo(16);
        verify(mapper).upsertById(any(AiCallUsageEntity.class));
    }

    @Test
    void successfulProviderUsageSettlesUserQuota() {
        AiCallUsageMapper mapper = mock(AiCallUsageMapper.class);
        when(mapper.upsertById(any(AiCallUsageEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserAccountMapper userMapper = mock(UserAccountMapper.class);
        when(userMapper.selectOptionalById("user-a")).thenReturn(Optional.empty());
        TokenQuotaService quotaService = mock(TokenQuotaService.class);
        ObjectProvider<TokenQuotaService> quotaProvider = quotaProvider(quotaService);
        AiCallUsageService service = new AiCallUsageService(mapper, userMapper, quotaProvider);
        PipelineContext ctx = contextWithUsage(12, 4, 16);
        AuthUser user = new AuthUser("user-a", "alice", true);

        service.recordSuccess("echomind.chat.stream.consume", user, ctx, System.nanoTime());

        verify(mapper).upsertById(any(AiCallUsageEntity.class));
        verify(quotaService).settleUsage(user, 16);
    }

    @Test
    void quotaSettlementFailureKeepsAuditWriteAndRethrowsQuotaException() {
        AiCallUsageMapper mapper = mock(AiCallUsageMapper.class);
        when(mapper.upsertById(any(AiCallUsageEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserAccountMapper userMapper = mock(UserAccountMapper.class);
        when(userMapper.selectOptionalById("user-a")).thenReturn(Optional.empty());
        TokenQuotaService quotaService = mock(TokenQuotaService.class);
        ObjectProvider<TokenQuotaService> quotaProvider = quotaProvider(quotaService);
        AiCallUsageService service = new AiCallUsageService(mapper, userMapper, quotaProvider);
        PipelineContext ctx = contextWithUsage(12, 4, 16);
        AuthUser user = new AuthUser("user-a", "alice", true);
        TokenQuotaExceededException quotaError = new TokenQuotaExceededException("user-a", "daily", 116, 100);
        doThrow(quotaError).when(quotaService).settleUsage(eq(user), eq(16L));

        assertThatThrownBy(() -> service.recordSuccess("echomind.chat.stream.consume", user, ctx, System.nanoTime()))
            .isSameAs(quotaError);

        verify(mapper).upsertById(any(AiCallUsageEntity.class));
        verify(quotaService).settleUsage(user, 16);
    }

    @Test
    void skipsQuotaSettlementWhenQuotaServiceIsUnavailable() {
        AiCallUsageMapper mapper = mock(AiCallUsageMapper.class);
        when(mapper.upsertById(any(AiCallUsageEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserAccountMapper userMapper = mock(UserAccountMapper.class);
        when(userMapper.selectOptionalById("user-a")).thenReturn(Optional.empty());
        ObjectProvider<TokenQuotaService> quotaProvider = quotaProvider(null);
        AiCallUsageService service = new AiCallUsageService(mapper, userMapper, quotaProvider);

        service.recordSuccess("echomind.chat.stream.consume", new AuthUser("user-a", "alice", true),
            contextWithUsage(12, 4, 16), System.nanoTime());

        verify(mapper).upsertById(any(AiCallUsageEntity.class));
    }

    @Test
    void rejectsCallsWhenProviderUsageIsMissing() {
        AiCallUsageMapper mapper = mock(AiCallUsageMapper.class);
        AiCallUsageService service = new AiCallUsageService(mapper, mock(UserAccountMapper.class));
        PipelineContext ctx = new PipelineContext();
        ctx.setTraceId("trace-a");

        assertThatThrownBy(() -> service.recordSuccess(
            "echomind.chat.stream.consume",
            new AuthUser("user-a", "alice", true),
            ctx,
            System.nanoTime()
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("模型未返回原生 token usage");
        verify(mapper, never()).upsertById(any());
    }

    private PipelineContext contextWithUsage(long promptTokens, long completionTokens, long totalTokens) {
        PipelineContext ctx = new PipelineContext();
        ctx.setTraceId("trace-a");
        ctx.setUserId("user-a");
        ctx.setAgentId("default");
        ctx.setSessionId("session-a");
        ctx.setModelId("deepseek:deepseek-v4-flash");
        ctx.setFinalResponse("ok");
        ctx.setTokenUsage(new TokenUsage(promptTokens, completionTokens, totalTokens));
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<TokenQuotaService> quotaProvider(TokenQuotaService quotaService) {
        ObjectProvider<TokenQuotaService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(quotaService);
        return provider;
    }
}
