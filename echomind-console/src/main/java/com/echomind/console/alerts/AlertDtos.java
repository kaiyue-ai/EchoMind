package com.echomind.console.alerts;

import java.time.Instant;
import java.util.List;

public final class AlertDtos {
    private AlertDtos() {
    }

    public record AlertRuleView(
        String ruleId,
        AlertType alertType,
        String ruleName,
        AlertSeverity severity,
        boolean enabled,
        Double thresholdPercent,
        Integer windowMinutes,
        int quietMinutes,
        Boolean escalationEnabled,
        Integer escalationThreshold,
        Instant updatedAt
    ) {
    }

    public record AlertRuleListResponse(List<AlertRuleView> rules, boolean defaultWebhookConfigured) {
    }

    public record UpdateAlertRulesRequest(List<AlertRuleView> rules) {
    }

    public record AlertEventView(
        String eventId,
        AlertType alertType,
        AlertSeverity severity,
        AlertStatus status,
        String traceId,
        String userId,
        String username,
        String agentId,
        String sessionId,
        String title,
        String message,
        String suggestion,
        String failureReason,
        boolean escalated,
        int suppressedCount,
        String providerResponse,
        Instant createdAt
    ) {
    }

    public record AlertEventListResponse(List<AlertEventView> events) {
    }
}
