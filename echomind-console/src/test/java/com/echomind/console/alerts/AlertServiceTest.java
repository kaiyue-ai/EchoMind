package com.echomind.console.alerts;

import com.echomind.console.sensitive.SensitiveAction;
import com.echomind.console.sensitive.SensitiveDirection;
import com.echomind.console.sensitive.SensitiveEventEntity;
import com.echomind.console.usage.AiCallUsageMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
        AlertService service = new AlertService(ruleMapper, eventMapper, mock(AiCallUsageMapper.class), feishu);

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
        AlertService service = new AlertService(ruleMapper, eventMapper, mock(AiCallUsageMapper.class), feishu);

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
        AlertService service = new AlertService(ruleMapper, eventMapper, mock(AiCallUsageMapper.class), feishu);

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
        AlertService service = new AlertService(ruleMapper, eventMapper, mock(AiCallUsageMapper.class), feishu);

        service.emitSensitiveEvent(sensitiveEvent());

        verify(feishu, never()).send(any());
        verify(eventMapper).upsertById(argThat(event ->
            event.getStatus() == AlertStatus.SILENCED && event.getSuppressedCount() == 6));
    }

    private AlertRuleEntity activeRule() {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setRuleId("sensitive");
        rule.setAlertType(AlertType.SENSITIVE_DATA);
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
