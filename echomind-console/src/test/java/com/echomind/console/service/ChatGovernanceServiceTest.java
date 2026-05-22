package com.echomind.console.service;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.console.alerts.AlertService;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.quota.TokenQuotaService;
import com.echomind.console.sensitive.SensitiveDataService;
import com.echomind.console.usage.AiCallUsageService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatGovernanceServiceTest {

    @Test
    void emitCallErrorIfFailedUsesStructuredFailureReason() {
        AlertService alertService = mock(AlertService.class);
        ChatGovernanceService service = service(alertService, mock(TokenQuotaService.class),
            passthroughSensitiveService(), mock(AiCallUsageService.class));
        AuthUser user = new AuthUser("user-a", "alice", true);
        PipelineContext ctx = context();
        ctx.markFailed("provider timeout");

        boolean emitted = service.emitCallErrorIfFailed(user, ctx);

        assertThat(emitted).isTrue();
        verify(alertService).emitCallError(user, ctx, "provider timeout");
    }

    @Test
    void emitCallErrorIfFailedStripsLegacyErrorPrefix() {
        AlertService alertService = mock(AlertService.class);
        ChatGovernanceService service = service(alertService, mock(TokenQuotaService.class),
            passthroughSensitiveService(), mock(AiCallUsageService.class));
        AuthUser user = new AuthUser("user-a", "alice", true);
        PipelineContext ctx = context();
        ctx.setFinalResponse("[Error] LLM call failed: boom");

        boolean emitted = service.emitCallErrorIfFailed(user, ctx);

        assertThat(emitted).isTrue();
        verify(alertService).emitCallError(user, ctx, "LLM call failed: boom");
    }

    @Test
    void emitCallErrorIfFailedIgnoresSuccessfulContext() {
        AlertService alertService = mock(AlertService.class);
        ChatGovernanceService service = service(alertService, mock(TokenQuotaService.class),
            passthroughSensitiveService(), mock(AiCallUsageService.class));
        PipelineContext ctx = context();
        ctx.setFinalResponse("ok");

        boolean emitted = service.emitCallErrorIfFailed(new AuthUser("user-a", "alice", true), ctx);

        assertThat(emitted).isFalse();
        verify(alertService, never()).emitCallError(any(), any(), anyString());
    }

    @Test
    void inspectRequestRunsQuotaBeforeSensitiveMasking() {
        TokenQuotaService quotaService = mock(TokenQuotaService.class);
        SensitiveDataService sensitiveDataService = mock(SensitiveDataService.class);
        ChatGovernanceService service = service(mock(AlertService.class), quotaService,
            sensitiveDataService, mock(AiCallUsageService.class));
        AuthUser user = new AuthUser("user-a", "alice", true);
        when(sensitiveDataService.inspectRequest(any(), anyString(), eq("agent-a"), eq("session-a"), eq("call 13800138000")))
            .thenReturn(new SensitiveDataService.GovernedText("call [PHONE]", true));

        String governed = service.inspectRequest(io.opentelemetry.api.trace.Span.getInvalid(), user,
            "agent-a", "session-a", "call 13800138000");

        assertThat(governed).isEqualTo("call [PHONE]");
        verify(quotaService).assertAllowed(user);
        verify(sensitiveDataService).inspectRequest(any(), anyString(), eq("agent-a"), eq("session-a"),
            eq("call 13800138000"));
    }

    @Test
    void recordSuccessSkipsUsageWhenModelWasNotInvoked() {
        AlertService alertService = mock(AlertService.class);
        TokenQuotaService quotaService = mock(TokenQuotaService.class);
        AiCallUsageService usageService = mock(AiCallUsageService.class);
        ChatGovernanceService service = service(alertService, quotaService,
            passthroughSensitiveService(), usageService);
        AuthUser user = new AuthUser("user-a", "alice", true);
        PipelineContext ctx = context();
        ctx.setFinalResponse("工具操作失败: 参数不完整");
        ctx.getAttributes().put("modelUsageNotApplicable", true);
        when(quotaService.warningSignals(user)).thenReturn(List.of());

        assertThat(service.recordSuccessAndWarnings(io.opentelemetry.api.trace.Span.getInvalid(),
            "echomind.chat.sync", user, ctx, System.nanoTime())).isNull();

        verify(usageService, never()).recordSuccess(anyString(), any(), any(), anyLong());
        verify(alertService).emitQuotaWarning(user, "trace-a", "agent-a", "session-a", List.of());
    }

    private PipelineContext context() {
        PipelineContext ctx = new PipelineContext();
        ctx.setTraceId("trace-a");
        ctx.setAgentId("agent-a");
        ctx.setSessionId("session-a");
        return ctx;
    }

    private ChatGovernanceService service(AlertService alertService, TokenQuotaService quotaService,
                                          SensitiveDataService sensitiveDataService,
                                          AiCallUsageService usageService) {
        return new ChatGovernanceService(usageService, quotaService, sensitiveDataService, alertService);
    }

    private SensitiveDataService passthroughSensitiveService() {
        SensitiveDataService service = mock(SensitiveDataService.class);
        when(service.inspectRequest(any(), anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> new SensitiveDataService.GovernedText(invocation.getArgument(4), false));
        when(service.inspectResponse(any(), anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> new SensitiveDataService.GovernedText(invocation.getArgument(4), false));
        return service;
    }
}
