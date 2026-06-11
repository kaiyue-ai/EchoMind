package com.echomind.console.sensitive;

import com.echomind.console.alerts.AlertService;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.sensitive.SensitiveDtos.SensitiveEventListResponse;
import com.echomind.console.sensitive.SensitiveDtos.SensitiveEventView;
import com.echomind.console.sensitive.SensitiveDtos.SensitiveRuleListResponse;
import com.echomind.console.sensitive.SensitiveDtos.SensitiveRuleView;
import com.echomind.console.sensitive.SensitiveDtos.UpdateSensitiveRulesRequest;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
@RequiredArgsConstructor
public class SensitiveDataService {

    private static final int SAMPLE_LIMIT = 1000;

    private final SensitiveRuleMapper ruleMapper;
    private final SensitiveEventMapper eventMapper;
    private final AlertService alertService;
    private final SensitiveRuleCache ruleCache;
    private final ConcurrentMap<String, Pattern> compiledPatternCache = new ConcurrentHashMap<>();

    @Transactional
    public GovernedText inspectRequest(AuthUser user, String traceId, String agentId, String sessionId, String text) {
        return inspectRequestText(user, traceId, agentId, sessionId, text);
    }

    @Transactional
    public GovernedText inspectResponse(AuthUser user, String traceId, String agentId, String sessionId, String text) {
        return inspect(SensitiveDirection.RESPONSE, user, traceId, agentId, sessionId, text,
            ruleCache.enabledRules(this::loadEnabledRulesWithDefaults));
    }

    @Transactional
    public SensitiveRuleListResponse listRules() {
        return new SensitiveRuleListResponse(ruleCache.allRules(this::loadAllRulesWithDefaults).stream()
            .map(this::view)
            .toList());
    }

    @Transactional
    public SensitiveRuleListResponse updateRules(UpdateSensitiveRulesRequest request) {
        ensureDefaultRules();
        if (request != null && request.rules() != null) {
            for (SensitiveRuleView view : request.rules()) {
                if (view == null) {
                    continue;
                }
                validatePattern(view.pattern());
                SensitiveRuleEntity entity = view.ruleId() == null || view.ruleId().isBlank()
                    ? new SensitiveRuleEntity()
                    : ruleMapper.selectOptionalById(view.ruleId()).orElseGet(SensitiveRuleEntity::new);
                entity.setRuleId(blankToNull(view.ruleId()));
                entity.setRuleName(required(view.ruleName(), "规则名称不能为空"));
                entity.setPattern(required(view.pattern(), "匹配表达式不能为空"));
                entity.setReplacement(defaultValue(view.replacement(), "[" + entity.getRuleName() + "]"));
                entity.setAction(view.action() == null ? SensitiveAction.MASK : view.action());
                entity.setEnabled(view.enabled());
                entity.setBuiltIn(view.builtIn());
                ruleMapper.upsertById(entity);
            }
        }
        List<SensitiveRuleEntity> rules = ruleMapper.selectAll();
        invalidateRulesAfterCommit();
        return new SensitiveRuleListResponse(rules.stream()
            .map(this::view)
            .toList());
    }

    @Transactional(readOnly = true)
    public SensitiveEventListResponse listEvents(Integer limit) {
        int safeLimit = Math.max(1, Math.min(500, limit == null ? 100 : limit));
        return new SensitiveEventListResponse(eventMapper.selectLatestOrderByCreatedAtDesc(safeLimit)
            .stream()
            .map(this::eventView)
            .toList());
    }

    private GovernedText inspectRequestText(AuthUser user, String traceId, String agentId, String sessionId,
                                            String text) {
        String original = text == null ? "" : text;
        List<SensitiveRuleEntity> rules = ruleCache.enabledRules(this::loadEnabledRulesWithDefaults);
        List<BlockMatch> blockMatches = collectBlockMatches(rules, original);
        if (!blockMatches.isEmpty()) {
            String replacementText = blockMatches.stream()
                .sorted(Comparator
                    .comparingInt(BlockMatch::start)
                    .thenComparingInt(BlockMatch::end)
                    .thenComparingInt(BlockMatch::ruleOrder))
                .map(match -> match.rule().getReplacement())
                .reduce((left, right) -> left + " " + right)
                .orElse("");
            for (Map.Entry<SensitiveRuleEntity, Integer> entry : countByRule(blockMatches).entrySet()) {
                SensitiveRuleEntity rule = entry.getKey();
                SensitiveEventEntity event = recordEvent(SensitiveDirection.REQUEST, user, traceId, agentId,
                    sessionId, rule, entry.getValue(), replacementText);
                alertService.emitSensitiveEvent(event);
                tagCurrentSpan(rule, SensitiveDirection.REQUEST, entry.getValue());
            }
            return new GovernedText(replacementText, true, true);
        }
        return inspect(SensitiveDirection.REQUEST, user, traceId, agentId, sessionId, original, rules);
    }

    private GovernedText inspect(SensitiveDirection direction, AuthUser user, String traceId, String agentId,
                                 String sessionId, String text, List<SensitiveRuleEntity> rules) {
        String current = text == null ? "" : text;
        boolean touched = false;
        for (SensitiveRuleEntity rule : rules) {
            Pattern pattern = compile(rule.getPattern());
            Matcher matcher = pattern.matcher(current);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            if (count == 0) {
                continue;
            }
            touched = true;
            String masked = pattern.matcher(current).replaceAll(Matcher.quoteReplacement(rule.getReplacement()));
            SensitiveEventEntity event = recordEvent(direction, user, traceId, agentId, sessionId, rule, count, masked);
            alertService.emitSensitiveEvent(event);
            tagCurrentSpan(rule, direction, count);
            if (rule.getAction() == SensitiveAction.BLOCK) {
                throw new SensitiveDataBlockedException(direction, rule.getRuleName());
            }
            current = masked;
        }
        return new GovernedText(current, touched);
    }

    private List<BlockMatch> collectBlockMatches(List<SensitiveRuleEntity> rules, String text) {
        List<BlockMatch> matches = new ArrayList<>();
        for (int ruleOrder = 0; ruleOrder < rules.size(); ruleOrder++) {
            SensitiveRuleEntity rule = rules.get(ruleOrder);
            if (rule.getAction() != SensitiveAction.BLOCK) {
                continue;
            }
            Pattern pattern = compile(rule.getPattern());
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                matches.add(new BlockMatch(rule, matcher.start(), matcher.end(), ruleOrder));
            }
        }
        return matches;
    }

    private Map<SensitiveRuleEntity, Integer> countByRule(List<BlockMatch> matches) {
        Map<SensitiveRuleEntity, Integer> counts = new LinkedHashMap<>();
        for (BlockMatch match : matches) {
            counts.merge(match.rule(), 1, Integer::sum);
        }
        return counts;
    }

    private List<SensitiveRuleEntity> loadAllRulesWithDefaults() {
        ensureDefaultRules();
        return ruleMapper.selectAll();
    }

    private List<SensitiveRuleEntity> loadEnabledRulesWithDefaults() {
        ensureDefaultRules();
        return ruleMapper.selectEnabledOrderByRuleNameAsc();
    }

    private SensitiveEventEntity recordEvent(SensitiveDirection direction, AuthUser user, String traceId, String agentId,
                                             String sessionId, SensitiveRuleEntity rule, int count, String sample) {
        AuthUser owner = user == null ? AuthUser.DEFAULT : user;
        SensitiveEventEntity event = new SensitiveEventEntity();
        event.setTraceId(blankToFallback(traceId, Span.current().getSpanContext().getTraceId()));
        event.setUserId(blankToFallback(owner.userId(), AuthUser.DEFAULT_USER_ID));
        event.setUsername(owner.authenticated() ? owner.username() : "default");
        event.setAgentId(agentId);
        event.setSessionId(sessionId);
        event.setRuleId(rule.getRuleId());
        event.setRuleName(rule.getRuleName());
        event.setDirection(direction);
        event.setAction(rule.getAction());
        event.setMatchCount(count);
        event.setSample(truncate(sample, SAMPLE_LIMIT));
        return eventMapper.upsertById(event);
    }

    private void ensureDefaultRules() {
        if (ruleMapper.selectCountAll() > 0) {
            return;
        }
        defaultRules().forEach(ruleMapper::upsertById);
        invalidateRulesAfterCommit();
    }

    private void invalidateRulesAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidateRuleCaches();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateRuleCaches();
            }
        });
    }

    private void invalidateRuleCaches() {
        ruleCache.invalidateRules();
        compiledPatternCache.clear();
    }

    private List<SensitiveRuleEntity> defaultRules() {
        return List.of(
            defaultRule("phone", "手机号", "(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[PHONE]"),
            defaultRule("id-card", "身份证", "(?<!\\d)\\d{17}[\\dXx](?!\\d)", "[ID_CARD]"),
            defaultRule("email", "邮箱", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL]"),
            defaultRule("bank-card", "银行卡", "(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)", "[BANK_CARD]"),
            defaultRule("ip-address", "IP地址", "\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\b", "[IP]")
        );
    }

    private SensitiveRuleEntity defaultRule(String id, String name, String pattern, String replacement) {
        SensitiveRuleEntity rule = new SensitiveRuleEntity();
        rule.setRuleId(id);
        rule.setRuleName(name);
        rule.setPattern(pattern);
        rule.setReplacement(replacement);
        rule.setAction(SensitiveAction.MASK);
        rule.setEnabled(true);
        rule.setBuiltIn(true);
        return rule;
    }

    private SensitiveRuleView view(SensitiveRuleEntity entity) {
        return new SensitiveRuleView(
            entity.getRuleId(),
            entity.getRuleName(),
            entity.getPattern(),
            entity.getReplacement(),
            entity.getAction(),
            entity.isEnabled(),
            entity.isBuiltIn(),
            entity.getUpdatedAt()
        );
    }

    private SensitiveEventView eventView(SensitiveEventEntity event) {
        return new SensitiveEventView(
            event.getEventId(),
            event.getTraceId(),
            event.getUserId(),
            event.getUsername(),
            event.getAgentId(),
            event.getSessionId(),
            event.getRuleId(),
            event.getRuleName(),
            event.getDirection(),
            event.getAction(),
            event.getMatchCount(),
            event.getSample(),
            event.getCreatedAt()
        );
    }

    private void tagCurrentSpan(SensitiveRuleEntity rule, SensitiveDirection direction, int count) {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) {
            return;
        }
        span.setAttribute("echomind.sensitive.triggered", true);
        span.setAttribute("echomind.sensitive.direction", direction.name());
        span.setAttribute("echomind.sensitive.rule", rule.getRuleName());
        span.setAttribute("echomind.sensitive.match_count", count);
        span.setAttribute("echomind.sensitive.action", rule.getAction().name());
    }

    private Pattern compile(String pattern) {
        return compiledPatternCache.computeIfAbsent(pattern, this::compileUncached);
    }

    private Pattern compileUncached(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("敏感数据规则正则无效: " + e.getDescription());
        }
    }

    private void validatePattern(String pattern) {
        compile(required(pattern, "匹配表达式不能为空"));
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record BlockMatch(SensitiveRuleEntity rule, int start, int end, int ruleOrder) {
    }

    public record GovernedText(String text, boolean changed, boolean blocked) {
        public GovernedText(String text, boolean changed) {
            this(text, changed, false);
        }
    }
}
