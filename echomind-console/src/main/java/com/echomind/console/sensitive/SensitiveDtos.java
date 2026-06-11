package com.echomind.console.sensitive;

import java.time.Instant;
import java.util.List;
// 敏感数据
public final class SensitiveDtos {
    private SensitiveDtos() {
    }

    public record SensitiveRuleView(
        String ruleId, // 规则ID
        String ruleName, // 规则名称
        String pattern, // 匹配的正则表达式
        String replacement, // 代替的词语
        SensitiveAction action, // 阻塞 or 代替
        boolean enabled, // 是否启用
        boolean builtIn, // 是否内置规则
        Instant updatedAt // 更新的时间
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
