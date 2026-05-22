package com.echomind.console.sensitive;

import com.echomind.console.alerts.AlertService;
import com.echomind.console.auth.AuthUser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SensitiveDataServiceTest {

    @Test
    void masksSensitiveTextAndRecordsSanitizedEvent() {
        SensitiveRuleRepository ruleRepository = mock(SensitiveRuleRepository.class);
        SensitiveEventRepository eventRepository = mock(SensitiveEventRepository.class);
        AlertService alertService = mock(AlertService.class);
        SensitiveRuleEntity rule = rule("email", "邮箱", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL]",
            SensitiveAction.MASK);
        when(ruleRepository.count()).thenReturn(1L);
        when(ruleRepository.findByEnabledTrueOrderByRuleNameAsc()).thenReturn(List.of(rule));
        when(eventRepository.save(any(SensitiveEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SensitiveDataService service = new SensitiveDataService(ruleRepository, eventRepository, alertService);

        SensitiveDataService.GovernedText result = service.inspectRequest(
            new AuthUser("user-a", "alice", true),
            "trace-a",
            "default",
            "session-a",
            "请联系 alice@example.com"
        );

        assertThat(result.text()).isEqualTo("请联系 [EMAIL]");
        verify(eventRepository).save(any(SensitiveEventEntity.class));
        verify(alertService).emitSensitiveEvent(any(SensitiveEventEntity.class));
    }

    @Test
    void blocksWhenRuleUsesBlockAction() {
        SensitiveRuleRepository ruleRepository = mock(SensitiveRuleRepository.class);
        SensitiveEventRepository eventRepository = mock(SensitiveEventRepository.class);
        AlertService alertService = mock(AlertService.class);
        SensitiveRuleEntity rule = rule("phone", "手机号", "(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[PHONE]",
            SensitiveAction.BLOCK);
        when(ruleRepository.count()).thenReturn(1L);
        when(ruleRepository.findByEnabledTrueOrderByRuleNameAsc()).thenReturn(List.of(rule));
        when(eventRepository.save(any(SensitiveEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SensitiveDataService service = new SensitiveDataService(ruleRepository, eventRepository, alertService);

        assertThatThrownBy(() -> service.inspectResponse(
            new AuthUser("user-a", "alice", true),
            "trace-a",
            "default",
            "session-a",
            "手机号 13800138000"
        ))
            .isInstanceOf(SensitiveDataBlockedException.class)
            .hasMessageContaining("手机号");
        verify(eventRepository).save(any(SensitiveEventEntity.class));
        verify(alertService).emitSensitiveEvent(any(SensitiveEventEntity.class));
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
