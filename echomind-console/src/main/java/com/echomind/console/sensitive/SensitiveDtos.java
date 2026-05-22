package com.echomind.console.sensitive;

import java.time.Instant;
import java.util.List;

public final class SensitiveDtos {
    private SensitiveDtos() {
    }

    public record SensitiveRuleView(
        String ruleId,
        String ruleName,
        String pattern,
        String replacement,
        SensitiveAction action,
        boolean enabled,
        boolean builtIn,
        Instant updatedAt
    ) {
    }

    public record SensitiveRuleListResponse(List<SensitiveRuleView> rules) {
    }

    public record UpdateSensitiveRulesRequest(List<SensitiveRuleView> rules) {
    }

    public record SensitiveEventView(
        String eventId,
        String traceId,
        String userId,
        String username,
        String agentId,
        String sessionId,
        String ruleId,
        String ruleName,
        SensitiveDirection direction,
        SensitiveAction action,
        int matchCount,
        String sample,
        Instant createdAt
    ) {
    }

    public record SensitiveEventListResponse(List<SensitiveEventView> events) {
    }
}
