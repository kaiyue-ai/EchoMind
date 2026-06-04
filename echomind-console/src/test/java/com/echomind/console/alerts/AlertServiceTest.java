package com.echomind.console.alerts;

import com.echomind.console.sensitive.SensitiveAction;
import com.echomind.console.sensitive.SensitiveDirection;
import com.echomind.console.sensitive.SensitiveEventEntity;
import com.echomind.console.usage.AiCallUsageMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertServiceTest {

    @Test
    void sendsFeishuAlertWhenRuleIsActiveAndNotSilenced() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        FeishuWebhookClient feishu = mock(FeishuWebhookClient.class);
        AlertRuleEntity rule = activeRule();
        when(ruleMapper.selectOneByAlertType(any(AlertType.class))).thenReturn(Optional.of(rule));
        when(eventMapper.existsByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(any(), any(), any()))
            .thenReturn(false);
        when(feishu.send(any())).thenReturn(new FeishuWebhookClient.SendResult(AlertStatus.SENT, null));
        when(eventMapper.upsertById(any(AlertEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlertService service = service(ruleMapper, eventMapper, feishu);

        service.emitSensitiveEvent(sensitiveEvent());

        verify(feishu).send(any(AlertEventEntity.class));
        verify(eventMapper).upsertById(any(AlertEventEntity.class));
    }

    @Test
    void createsSilencedEventWithoutSendingWebhookInsideQuietWindow() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        FeishuWebhookClient feishu = mock(FeishuWebhookClient.class);
        AlertRuleEntity rule = activeRule();
        when(ruleMapper.selectOneByAlertType(any(AlertType.class))).thenReturn(Optional.of(rule));
        when(eventMapper.existsByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(any(), any(), any()))
            .thenReturn(true);
        when(eventMapper.upsertById(any(AlertEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlertService service = service(ruleMapper, eventMapper, feishu);

        service.emitSensitiveEvent(sensitiveEvent());

        verify(feishu, never()).send(any());
        verify(eventMapper).upsertById(
            org.mockito.ArgumentMatchers.argThat(event -> event.getStatus() == AlertStatus.SILENCED));
    }

    @Test
    void sendsEscalatedAlertWhenQuietWindowSuppressionReachesThreshold() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        FeishuWebhookClient feishu = mock(FeishuWebhookClient.class);
        AlertRuleEntity rule = activeRule();
        rule.setEscalationThreshold(3);
        when(ruleMapper.selectOneByAlertType(any(AlertType.class))).thenReturn(Optional.of(rule));
        when(eventMapper.existsByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(any(), any(), any()))
            .thenReturn(true);
        when(eventMapper.countByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(any(), any(), any()))
            .thenReturn(2L);
        when(eventMapper.existsByAlertTypeAndEscalatedTrueAndCreatedAtGreaterThanEqual(any(), any()))
            .thenReturn(false);
        when(feishu.send(any())).thenReturn(new FeishuWebhookClient.SendResult(AlertStatus.SENT, null, "ok"));
        when(eventMapper.upsertById(any(AlertEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlertService service = service(ruleMapper, eventMapper, feishu);

        service.emitSensitiveEvent(sensitiveEvent());

        verify(eventMapper).upsertById(argThat(event ->
            event.getStatus() == AlertStatus.SILENCED && event.getSuppressedCount() == 3));
        verify(feishu).send(argThat(event ->
            event.isEscalated()
                && event.getSeverity() == AlertSeverity.CRITICAL
                && event.getSuppressedCount() == 3));
        verify(eventMapper).upsertById(argThat(event ->
            event.isEscalated() && event.getStatus() == AlertStatus.SENT));
    }

    @Test
    void doesNotSendDuplicateEscalationInsideSameQuietWindow() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        FeishuWebhookClient feishu = mock(FeishuWebhookClient.class);
        AlertRuleEntity rule = activeRule();
        rule.setEscalationThreshold(3);
        when(ruleMapper.selectOneByAlertType(any(AlertType.class))).thenReturn(Optional.of(rule));
        when(eventMapper.existsByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(any(), any(), any()))
            .thenReturn(true);
        when(eventMapper.countByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(any(), any(), any()))
            .thenReturn(5L);
        when(eventMapper.existsByAlertTypeAndEscalatedTrueAndCreatedAtGreaterThanEqual(any(), any()))
            .thenReturn(true);
        when(eventMapper.upsertById(any(AlertEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlertService service = service(ruleMapper, eventMapper, feishu);

        service.emitSensitiveEvent(sensitiveEvent());

        verify(feishu, never()).send(any());
        verify(eventMapper).upsertById(argThat(event ->
            event.getStatus() == AlertStatus.SILENCED && event.getSuppressedCount() == 6));
    }

    @Test
    void readsActiveRuleThroughCacheWhenEmittingAlert() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        FeishuWebhookClient feishu = mock(FeishuWebhookClient.class);
        AlertRuleCache cache = mock(AlertRuleCache.class);
        AlertRuleEntity rule = activeRule();
        when(cache.ruleByType(eq(AlertType.SENSITIVE_DATA), any())).thenReturn(Optional.of(rule));
        when(cache.ruleByType(argThat(type -> type != AlertType.SENSITIVE_DATA), any()))
            .thenReturn(Optional.of(rule(AlertType.CALL_ERROR)));
        when(eventMapper.existsByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(any(), any(), any()))
            .thenReturn(false);
        when(feishu.send(any())).thenReturn(new FeishuWebhookClient.SendResult(AlertStatus.SENT, null));
        when(eventMapper.upsertById(any(AlertEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlertService service = new AlertService(ruleMapper, eventMapper, cache, mock(AiCallUsageMapper.class), feishu);

        service.emitSensitiveEvent(sensitiveEvent());

        verify(cache, org.mockito.Mockito.atLeastOnce()).ruleByType(eq(AlertType.SENSITIVE_DATA), any());
        verify(ruleMapper, never()).selectOneByAlertType(AlertType.SENSITIVE_DATA);
        verify(feishu).send(any(AlertEventEntity.class));
    }

    @Test
    void updateRulesInvalidatesCacheAfterWritingRule() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        FeishuWebhookClient feishu = mock(FeishuWebhookClient.class);
        AlertRuleCache cache = mock(AlertRuleCache.class);
        AlertRuleEntity existing = rule(AlertType.SENSITIVE_DATA);
        when(cache.ruleByType(any(AlertType.class), any())).thenReturn(Optional.of(existing));
        when(cache.allRules(any())).thenReturn(List.of(existing));
        when(ruleMapper.selectOptionalById("sensitive")).thenReturn(Optional.of(existing));
        AlertService service = new AlertService(ruleMapper, eventMapper, cache, mock(AiCallUsageMapper.class), feishu);

        service.updateRules(new AlertDtos.UpdateAlertRulesRequest(List.of(new AlertDtos.AlertRuleView(
            "sensitive",
            AlertType.SENSITIVE_DATA,
            "敏感数据事件",
            AlertSeverity.CRITICAL,
            true,
            null,
            null,
            10,
            true,
            3,
            null
        ))));

        verify(ruleMapper).upsertById(existing);
        verify(cache).invalidateRules();
    }

    @Test
    void disabledCachedRuleDoesNotSendAlert() {
        AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        FeishuWebhookClient feishu = mock(FeishuWebhookClient.class);
        AlertRuleCache cache = mock(AlertRuleCache.class);
        AlertRuleEntity disabled = activeRule();
        disabled.setEnabled(false);
        when(cache.ruleByType(eq(AlertType.SENSITIVE_DATA), any())).thenReturn(Optional.of(disabled));
        when(cache.ruleByType(argThat(type -> type != AlertType.SENSITIVE_DATA), any()))
            .thenReturn(Optional.of(rule(AlertType.CALL_ERROR)));
        AlertService service = new AlertService(ruleMapper, eventMapper, cache, mock(AiCallUsageMapper.class), feishu);

        service.emitSensitiveEvent(sensitiveEvent());

        verify(feishu, never()).send(any());
        verify(eventMapper, never()).upsertById(any(AlertEventEntity.class));
    }

    private AlertService service(AlertRuleMapper ruleMapper, AlertEventMapper eventMapper, FeishuWebhookClient feishu) {
        return new AlertService(ruleMapper, eventMapper, AlertRuleCache.disabled(), mock(AiCallUsageMapper.class),
            feishu);
    }

    private AlertRuleEntity activeRule() {
        return rule(AlertType.SENSITIVE_DATA);
    }

    private AlertRuleEntity rule(AlertType type) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setRuleId(type.name());
        rule.setAlertType(type);
        rule.setRuleName("敏感数据事件");
        rule.setSeverity(AlertSeverity.WARNING);
        rule.setEnabled(true);
        rule.setQuietMinutes(30);
        rule.setEscalationEnabled(true);
        rule.setEscalationThreshold(3);
        return rule;
    }

    private SensitiveEventEntity sensitiveEvent() {
        SensitiveEventEntity event = new SensitiveEventEntity();
        event.setTraceId("trace-a");
        event.setUserId("user-a");
        event.setUsername("alice");
        event.setAgentId("default");
        event.setSessionId("session-a");
        event.setRuleName("邮箱");
        event.setDirection(SensitiveDirection.REQUEST);
        event.setAction(SensitiveAction.MASK);
        event.setMatchCount(1);
        return event;
    }
}
