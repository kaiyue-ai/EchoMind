package com.echomind.console.alerts;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.console.alerts.AlertDtos.AlertEventListResponse;
import com.echomind.console.alerts.AlertDtos.AlertEventView;
import com.echomind.console.alerts.AlertDtos.AlertRuleListResponse;
import com.echomind.console.alerts.AlertDtos.AlertRuleView;
import com.echomind.console.alerts.AlertDtos.UpdateAlertRulesRequest;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.sensitive.SensitiveAction;
import com.echomind.console.sensitive.SensitiveDirection;
import com.echomind.console.sensitive.SensitiveEventEntity;
import com.echomind.console.usage.AiCallUsageMapper;
import com.echomind.console.usage.TokenUsageSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRuleMapper ruleMapper;
    private final AlertEventMapper eventMapper;
    private final AiCallUsageMapper usageMapper;
    private final FeishuWebhookClient feishuWebhookClient;
    private final Clock clock = Clock.systemDefaultZone();

    @Transactional
    public AlertRuleListResponse listRules() {
        ensureDefaultRules();
        return new AlertRuleListResponse(ruleMapper.selectAllOrderByAlertTypeAsc().stream()
            .filter(rule -> !isHistoricalUserQuotaType(rule.getAlertType()))
            .map(this::ruleView)
            .toList(), feishuWebhookClient.hasDefaultWebhookUrl());
    }

    @Transactional
    public AlertRuleListResponse updateRules(UpdateAlertRulesRequest request) {
        ensureDefaultRules();
        if (request != null && request.rules() != null) {
            for (AlertRuleView view : request.rules()) {
                if (view == null || view.alertType() == null) {
                    continue;
                }
                if (isHistoricalUserQuotaType(view.alertType())) {
                    continue;
                }
                AlertRuleEntity entity = view.ruleId() == null || view.ruleId().isBlank()
                    ? ruleMapper.selectOneByAlertType(view.alertType()).orElseGet(AlertRuleEntity::new)
                    : ruleMapper.selectOptionalById(view.ruleId()).orElseGet(AlertRuleEntity::new);
                if (view.ruleId() != null && !view.ruleId().isBlank()) {
                    entity.setRuleId(view.ruleId());
                }
                entity.setAlertType(view.alertType());
                entity.setRuleName(defaultValue(view.ruleName(), defaultName(view.alertType())));
                entity.setSeverity(view.severity() == null ? AlertSeverity.WARNING : view.severity());
                entity.setEnabled(view.enabled());
                entity.setThresholdPercent(view.thresholdPercent());
                entity.setWindowMinutes(normalizePositive(view.windowMinutes()));
                entity.setQuietMinutes(Math.max(0, view.quietMinutes()));
                entity.setEscalationEnabled(view.escalationEnabled() == null || view.escalationEnabled());
                entity.setEscalationThreshold(normalizeEscalationThreshold(view.escalationThreshold()));
                ruleMapper.upsertById(entity);
            }
        }
        return listRules();
    }

    @Transactional(readOnly = true)
    public AlertEventListResponse listEvents(Integer limit) {
        int safeLimit = Math.max(1, Math.min(500, limit == null ? 100 : limit));
        return new AlertEventListResponse(eventMapper.selectLatestOrderByCreatedAtDesc(safeLimit)
            .stream()
            .map(this::eventView)
            .toList());
    }

    @Transactional
    public void emitSensitiveEvent(SensitiveEventEntity sensitiveEvent) {
        if (sensitiveEvent == null) {
            return;
        }
        AlertSeverity severity = sensitiveEvent.getAction() == SensitiveAction.BLOCK
            ? AlertSeverity.CRITICAL
            : AlertSeverity.WARNING;
        boolean responseEscape = sensitiveEvent.getDirection() == SensitiveDirection.RESPONSE;
        emit(AlertType.SENSITIVE_DATA, severity, sensitiveEvent.getTraceId(), sensitiveEvent.getUserId(),
            sensitiveEvent.getUsername(), sensitiveEvent.getAgentId(), sensitiveEvent.getSessionId(), null,
            (responseEscape ? "敏感数据响应逃逸：" : "敏感数据触发：") + sensitiveEvent.getRuleName(),
            "方向=" + sensitiveEvent.getDirection() + "，动作=" + sensitiveEvent.getAction()
                + "，命中次数=" + sensitiveEvent.getMatchCount(),
            sensitiveEvent.getAction() == SensitiveAction.BLOCK
                ? "检查客户端输入或模型输出，确认是否需要调整规则或业务提示词。"
                : "已完成脱敏，请复核是否需要切换为阻断模式。");
    }

    @Transactional
    public void emitProviderBudgetExceeded(String providerId, String traceId, String agentId, String sessionId,
                                           String scope, long usedTokens, long limitTokens) {
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        emit(AlertType.PROVIDER_TOKEN_BUDGET_EXCEEDED, AlertSeverity.CRITICAL, traceId, null, "platform",
            agentId, sessionId, providerId,
            "Provider Token 预算已超限",
            "provider=" + providerId + "，scope=" + scope + "，used=" + usedTokens
                + "，limit=" + limitTokens,
            "在告警中心提高该 Provider 平台预算、停用预算或切换默认模型。");
    }

    @Transactional
    public void emitProviderBudgetWarning(String providerId, String traceId, String agentId, String sessionId,
                                          String scope, long usedTokens, long limitTokens, double usagePercent) {
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        emit(AlertType.PROVIDER_TOKEN_BUDGET_WARNING, AlertSeverity.WARNING, traceId, null, "platform",
            agentId, sessionId, providerId,
            "Provider Token 预算接近阈值",
            "provider=" + providerId + "，scope=" + scope + "，used=" + usedTokens
                + "，limit=" + limitTokens + "，usage=" + String.format("%.2f%%", usagePercent),
            "关注该 Provider 平台总消耗，必要时调整预算或切换模型。");
    }

    @Transactional
    public void emitCallError(AuthUser user, PipelineContext ctx, String errorMessage) {
        AuthUser owner = user == null ? AuthUser.DEFAULT : user;
        emit(AlertType.CALL_ERROR, AlertSeverity.WARNING, ctx == null ? null : ctx.getTraceId(), owner.userId(),
            username(owner), ctx == null ? null : ctx.getAgentId(), ctx == null ? null : ctx.getSessionId(),
            providerId(ctx),
            "AI 调用失败",
            truncate(errorMessage, 1000),
            "按 TraceID 查看失败 Span，确认模型、工具或存储依赖是否异常。");
        maybeEmitErrorRate(owner, ctx);
    }

    private void maybeEmitErrorRate(AuthUser user, PipelineContext ctx) {
        AlertRuleEntity rule = activeRule(AlertType.ERROR_RATE);
        if (rule == null || rule.getThresholdPercent() == null) {
            return;
        }
        int window = rule.getWindowMinutes() == null || rule.getWindowMinutes() <= 0 ? 5 : rule.getWindowMinutes();
        Instant since = Instant.now(clock).minus(window, ChronoUnit.MINUTES);
        long total = usageMapper.countByUsageSourceAndCreatedAtGreaterThanEqual(TokenUsageSource.PROVIDER, since);
        if (total == 0) {
            return;
        }
        long errors = usageMapper.countByUsageSourceAndStatusAndCreatedAtGreaterThanEqual(
            TokenUsageSource.PROVIDER, "ERROR", since);
        double rate = errors * 100.0 / total;
        if (rate < rule.getThresholdPercent()) {
            return;
        }
        emitWithRule(rule, user == null ? AuthUser.DEFAULT : user, ctx,
            "AI 调用错误率超阈值",
            "最近 " + window + " 分钟错误率 " + String.format("%.2f%%", rate)
                + "，阈值 " + rule.getThresholdPercent() + "%。",
            "优先查看最近错误 Trace，确认底层模型或工具依赖状态。");
    }

    private void emit(AlertType type, AlertSeverity fallbackSeverity, String traceId, String userId, String username,
                      String agentId, String sessionId, String providerId, String title, String message,
                      String suggestion) {
        AlertRuleEntity rule = activeRule(type);
        if (rule == null) {
            return;
        }
        AlertEventEntity event = baseEvent(type, rule.getSeverity() == null ? fallbackSeverity : rule.getSeverity(),
            traceId, userId, username, agentId, sessionId, providerId, title, message, suggestion);
        finishAndSave(rule, event);
    }

    private void emitWithRule(AlertRuleEntity rule, AuthUser user, PipelineContext ctx, String title, String message,
                              String suggestion) {
        AlertEventEntity event = baseEvent(rule.getAlertType(), rule.getSeverity(),
            ctx == null ? null : ctx.getTraceId(),
            user.userId(),
            username(user),
            ctx == null ? null : ctx.getAgentId(),
            ctx == null ? null : ctx.getSessionId(),
            providerId(ctx),
            title,
            message,
            suggestion);
        finishAndSave(rule, event);
    }

    private void finishAndSave(AlertRuleEntity rule, AlertEventEntity event) {
        Instant now = Instant.now(clock);
        if (isSilenced(rule, event, now)) {
            Instant since = now.minus(rule.getQuietMinutes(), ChronoUnit.MINUTES);
            int suppressedCount = boundedCount(silencedCount(rule, event, since) + 1);
            event.setStatus(AlertStatus.SILENCED);
            event.setSuppressedCount(suppressedCount);
            eventMapper.upsertById(event);
            maybeEscalate(rule, event, since, suppressedCount);
            return;
        }
        sendAndSave(rule, event);
    }

    private void sendAndSave(AlertRuleEntity rule, AlertEventEntity event) {
        FeishuWebhookClient.SendResult result = feishuWebhookClient.send(event);
        event.setStatus(result.status());
        event.setFailureReason(truncate(result.failureReason(), 1000));
        event.setProviderResponse(truncate(result.providerResponse(), 1000));
        eventMapper.upsertById(event);
    }

    private void maybeEscalate(AlertRuleEntity rule, AlertEventEntity silencedEvent, Instant since, int suppressedCount) {
        if (!rule.isEscalationEnabled() || suppressedCount < rule.getEscalationThreshold()) {
            return;
        }
        if (escalationExists(rule, silencedEvent, since)) {
            return;
        }
        AlertEventEntity escalation = baseEvent(rule.getAlertType(), AlertSeverity.CRITICAL,
            silencedEvent.getTraceId(),
            silencedEvent.getUserId(),
            silencedEvent.getUsername(),
            silencedEvent.getAgentId(),
            silencedEvent.getSessionId(),
            silencedEvent.getProviderId(),
            "告警静默累计升级：" + silencedEvent.getTitle(),
            "同类告警在静默期内累计 " + suppressedCount + " 次。最近一次详情：" + silencedEvent.getMessage(),
            silencedEvent.getSuggestion());
        escalation.setEscalated(true);
        escalation.setSuppressedCount(suppressedCount);
        sendAndSave(rule, escalation);
    }

    private boolean isSilenced(AlertRuleEntity rule, AlertEventEntity event, Instant now) {
        if (rule.getQuietMinutes() <= 0) {
            return false;
        }
        Instant since = now.minus(rule.getQuietMinutes(), ChronoUnit.MINUTES);
        if (isProviderBudgetType(rule.getAlertType())) {
            return eventMapper.existsByAlertTypeAndProviderIdAndStatusAndCreatedAtGreaterThanEqual(
                rule.getAlertType(), event.getProviderId(), AlertStatus.SENT, since);
        }
        return eventMapper.existsByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(
            rule.getAlertType(), AlertStatus.SENT, since);
    }

    private AlertRuleEntity activeRule(AlertType type) {
        ensureDefaultRules();
        return ruleMapper.selectOneByAlertType(type)
            .filter(AlertRuleEntity::isEnabled)
            .orElse(null);
    }

    private AlertEventEntity baseEvent(AlertType type, AlertSeverity severity, String traceId, String userId,
                                       String username, String agentId, String sessionId, String providerId,
                                       String title, String message, String suggestion) {
        AlertEventEntity event = new AlertEventEntity();
        event.setAlertType(type);
        event.setSeverity(severity == null ? AlertSeverity.WARNING : severity);
        event.setTraceId(traceId);
        event.setUserId(userId);
        event.setUsername(username);
        event.setAgentId(agentId);
        event.setSessionId(sessionId);
        event.setProviderId(providerId);
        event.setTitle(defaultValue(title, defaultName(type)));
        event.setMessage(truncate(message, 2000));
        event.setSuggestion(truncate(suggestion, 1000));
        return event;
    }

    private void ensureDefaultRules() {
        for (AlertType type : AlertType.values()) {
            if (isHistoricalUserQuotaType(type)) {
                continue;
            }
            if (ruleMapper.selectOneByAlertType(type).isPresent()) {
                continue;
            }
            ruleMapper.upsertById(defaultRule(type));
        }
    }

    private AlertRuleEntity defaultRule(AlertType type) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setAlertType(type);
        rule.setRuleName(defaultName(type));
        rule.setEnabled(true);
        rule.setSeverity(defaultSeverity(type));
        rule.setQuietMinutes(defaultQuietMinutes(type));
        rule.setEscalationEnabled(true);
        rule.setEscalationThreshold(3);
        if (type == AlertType.ERROR_RATE) {
            rule.setThresholdPercent(20.0);
            rule.setWindowMinutes(5);
        }
        return rule;
    }

    private String defaultName(AlertType type) {
        return switch (type) {
            case CALL_ERROR -> "调用错误";
            case ERROR_RATE -> "错误率阈值";
            case PROVIDER_TOKEN_BUDGET_EXCEEDED -> "Provider Token 预算超限";
            case PROVIDER_TOKEN_BUDGET_WARNING -> "Provider Token 预算预警";
            case TOKEN_QUOTA_EXCEEDED -> "用户 Token 配额超限（历史）";
            case TOKEN_QUOTA_WARNING -> "用户 Token 配额预警（历史）";
            case SENSITIVE_DATA -> "敏感数据事件";
        };
    }

    private AlertSeverity defaultSeverity(AlertType type) {
        return switch (type) {
            case PROVIDER_TOKEN_BUDGET_EXCEEDED, TOKEN_QUOTA_EXCEEDED -> AlertSeverity.CRITICAL;
            case SENSITIVE_DATA, CALL_ERROR, ERROR_RATE, PROVIDER_TOKEN_BUDGET_WARNING, TOKEN_QUOTA_WARNING ->
                AlertSeverity.WARNING;
        };
    }

    private int defaultQuietMinutes(AlertType type) {
        return switch (type) {
            case PROVIDER_TOKEN_BUDGET_WARNING, TOKEN_QUOTA_WARNING -> 120;
            case PROVIDER_TOKEN_BUDGET_EXCEEDED, TOKEN_QUOTA_EXCEEDED -> 60;
            case CALL_ERROR, ERROR_RATE -> 15;
            case SENSITIVE_DATA -> 30;
        };
    }

    private AlertRuleView ruleView(AlertRuleEntity rule) {
        return new AlertRuleView(
            rule.getRuleId(),
            rule.getAlertType(),
            rule.getRuleName(),
            rule.getSeverity(),
            rule.isEnabled(),
            rule.getThresholdPercent(),
            rule.getWindowMinutes(),
            rule.getQuietMinutes(),
            rule.isEscalationEnabled(),
            rule.getEscalationThreshold(),
            rule.getUpdatedAt()
        );
    }

    private AlertEventView eventView(AlertEventEntity event) {
        return new AlertEventView(
            event.getEventId(),
            event.getAlertType(),
            event.getSeverity(),
            event.getStatus(),
            event.getTraceId(),
            event.getUserId(),
            event.getUsername(),
            event.getAgentId(),
            event.getSessionId(),
            event.getProviderId(),
            event.getTitle(),
            event.getMessage(),
            event.getSuggestion(),
            event.getFailureReason(),
            event.isEscalated(),
            event.getSuppressedCount(),
            event.getProviderResponse(),
            event.getCreatedAt()
        );
    }

    private Integer normalizePositive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private int normalizeEscalationThreshold(Integer value) {
        if (value == null || value <= 0) {
            return 3;
        }
        return Math.min(1000, value);
    }

    private int boundedCount(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private long silencedCount(AlertRuleEntity rule, AlertEventEntity event, Instant since) {
        if (isProviderBudgetType(rule.getAlertType())) {
            return eventMapper.countByAlertTypeAndProviderIdAndStatusAndCreatedAtGreaterThanEqual(
                rule.getAlertType(), event.getProviderId(), AlertStatus.SILENCED, since);
        }
        return eventMapper.countByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(
            rule.getAlertType(), AlertStatus.SILENCED, since);
    }

    private boolean escalationExists(AlertRuleEntity rule, AlertEventEntity event, Instant since) {
        if (isProviderBudgetType(rule.getAlertType())) {
            return eventMapper.existsByAlertTypeAndProviderIdAndEscalatedTrueAndCreatedAtGreaterThanEqual(
                rule.getAlertType(), event.getProviderId(), since);
        }
        return eventMapper.existsByAlertTypeAndEscalatedTrueAndCreatedAtGreaterThanEqual(rule.getAlertType(), since);
    }

    private boolean isProviderBudgetType(AlertType type) {
        return type == AlertType.PROVIDER_TOKEN_BUDGET_WARNING
            || type == AlertType.PROVIDER_TOKEN_BUDGET_EXCEEDED;
    }

    private boolean isHistoricalUserQuotaType(AlertType type) {
        return type == AlertType.TOKEN_QUOTA_WARNING || type == AlertType.TOKEN_QUOTA_EXCEEDED;
    }

    private String providerId(PipelineContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (ctx.getResolvedModel() != null && ctx.getResolvedModel().providerId() != null
            && !ctx.getResolvedModel().providerId().isBlank()) {
            return ctx.getResolvedModel().providerId();
        }
        String modelId = ctx.getModelId();
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        int separator = modelId.indexOf(':');
        return separator > 0 ? modelId.substring(0, separator) : modelId;
    }

    private String username(AuthUser user) {
        if (user == null || !user.authenticated()) {
            return "default";
        }
        return user.username();
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
