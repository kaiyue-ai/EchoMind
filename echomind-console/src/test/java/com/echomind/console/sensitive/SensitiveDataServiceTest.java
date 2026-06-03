package com.echomind.console.sensitive;

import com.echomind.console.alerts.AlertService;
import com.echomind.console.auth.AuthUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SensitiveDataServiceTest {

    @Test
    void masksSensitiveTextAndRecordsSanitizedEvent() {
        SensitiveRuleMapper ruleMapper = mock(SensitiveRuleMapper.class);
        SensitiveEventMapper eventMapper = mock(SensitiveEventMapper.class);
        AlertService alertService = mock(AlertService.class);
        SensitiveRuleEntity rule = rule("email", "邮箱", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL]",
            SensitiveAction.MASK);
        when(ruleMapper.selectCountAll()).thenReturn(1L);
        when(ruleMapper.selectEnabledOrderByRuleNameAsc()).thenReturn(List.of(rule));
        when(eventMapper.upsertById(any(SensitiveEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SensitiveDataService service = service(ruleMapper, eventMapper, alertService);

        SensitiveDataService.GovernedText result = service.inspectRequest(
            new AuthUser("user-a", "alice", true),
            "trace-a",
            "default",
            "session-a",
            "请联系 alice@example.com"
        );

        assertThat(result.text()).isEqualTo("请联系 [EMAIL]");
        verify(eventMapper).upsertById(any(SensitiveEventEntity.class));
        verify(alertService).emitSensitiveEvent(any(SensitiveEventEntity.class));
    }

    @Test
    void blocksWhenRuleUsesBlockAction() {
        SensitiveRuleMapper ruleMapper = mock(SensitiveRuleMapper.class);
        SensitiveEventMapper eventMapper = mock(SensitiveEventMapper.class);
        AlertService alertService = mock(AlertService.class);
        SensitiveRuleEntity rule = rule("phone", "手机号", "(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[PHONE]",
            SensitiveAction.BLOCK);
        when(ruleMapper.selectCountAll()).thenReturn(1L);
        when(ruleMapper.selectEnabledOrderByRuleNameAsc()).thenReturn(List.of(rule));
        when(eventMapper.upsertById(any(SensitiveEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SensitiveDataService service = service(ruleMapper, eventMapper, alertService);

        assertThatThrownBy(() -> service.inspectResponse(
            new AuthUser("user-a", "alice", true),
            "trace-a",
            "default",
            "session-a",
            "手机号 13800138000"
        ))
            .isInstanceOf(SensitiveDataBlockedException.class)
            .hasMessageContaining("手机号");
        verify(eventMapper).upsertById(any(SensitiveEventEntity.class));
        verify(alertService).emitSensitiveEvent(any(SensitiveEventEntity.class));
    }

    @Test
    void updateRulesWritesDatabaseAndInvalidatesCache() {
        SensitiveRuleMapper ruleMapper = mock(SensitiveRuleMapper.class);
        SensitiveEventMapper eventMapper = mock(SensitiveEventMapper.class);
        AlertService alertService = mock(AlertService.class);
        SensitiveRuleCache ruleCache = mock(SensitiveRuleCache.class);
        SensitiveRuleEntity stored = rule("email", "邮箱", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
            "[EMAIL]", SensitiveAction.MASK);
        stored.setUpdatedAt(Instant.parse("2026-06-03T00:00:00Z"));
        when(ruleMapper.selectCountAll()).thenReturn(1L);
        when(ruleMapper.selectOptionalById("email")).thenReturn(java.util.Optional.of(stored));
        when(ruleMapper.selectAll()).thenReturn(List.of(stored));
        SensitiveDataService service = new SensitiveDataService(ruleMapper, eventMapper, alertService, ruleCache);

        SensitiveDtos.SensitiveRuleListResponse response = service.updateRules(
            new SensitiveDtos.UpdateSensitiveRulesRequest(List.of(new SensitiveDtos.SensitiveRuleView(
                "email",
                "邮箱",
                "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
                "[EMAIL]",
                SensitiveAction.MASK,
                true,
                true,
                stored.getUpdatedAt()
            )))
        );

        assertThat(response.rules()).hasSize(1);
        verify(ruleMapper).upsertById(stored);
        verify(ruleCache).invalidateRules();
        verify(ruleCache, never()).allRules(any());
    }

    private SensitiveDataService service(SensitiveRuleMapper ruleMapper, SensitiveEventMapper eventMapper,
                                         AlertService alertService) {
        return new SensitiveDataService(ruleMapper, eventMapper, alertService, SensitiveRuleCache.disabled());
    }

    private SensitiveRuleEntity rule(String id, String name, String pattern, String replacement, SensitiveAction action) {
        SensitiveRuleEntity rule = new SensitiveRuleEntity();
        rule.setRuleId(id);
        rule.setRuleName(name);
        rule.setPattern(pattern);
        rule.setReplacement(replacement);
        rule.setAction(action);
        rule.setEnabled(true);
        return rule;
    }
}
