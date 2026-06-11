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

/**
 * 告警服务
 *
 * <p>负责告警规则管理、告警事件触发、告警通知发送和告警静默/升级逻辑。
 * 支持多种告警类型：敏感数据事件、Provider预算告警、调用错误、错误率阈值等。</p>
 *
 * <p>核心特性：
 * <ul>
 *   <li>自动初始化默认告警规则</li>
 *   <li>告警静默机制（避免重复告警）</li>
 *   <li>告警升级机制（静默期内累计达到阈值时升级为CRITICAL）</li>
 *   <li>飞书 Webhook 通知推送</li>
 * </ul>
 * </p>
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    /** 告警规则数据访问层 */
    private final AlertRuleMapper ruleMapper;

    /** 告警事件数据访问层 */
    private final AlertEventMapper eventMapper;

    /** 告警规则 Redis 热缓存 */
    private final AlertRuleCache ruleCache;

    /** AI调用使用量数据访问层（用于错误率计算） */
    private final AiCallUsageMapper usageMapper;

    /** 飞书 Webhook 客户端（用于发送告警通知） */
    private final FeishuWebhookClient feishuWebhookClient;

    /** 系统时钟（默认使用系统默认时区） */
    private final Clock clock = Clock.systemDefaultZone();

    /**
     * 列出所有告警规则
     *
     * @return 告警规则列表响应
     */
    @Transactional
    public AlertRuleListResponse listRules() {
        ensureDefaultRules();
        return new AlertRuleListResponse(ruleCache.allRules(ruleMapper::selectAllOrderByAlertTypeAsc).stream()
            .map(this::ruleView)
            .toList(), feishuWebhookClient.hasDefaultWebhookUrl());
    }

    /**
     * 更新告警规则
     *
     * @param request 更新请求
     * @return 更新后的告警规则列表
     */
    @Transactional
    public AlertRuleListResponse updateRules(UpdateAlertRulesRequest request) {
        ensureDefaultRules();
        if (request != null && request.rules() != null) {
            for (AlertRuleView view : request.rules()) {
                if (view == null || view.alertType() == null) {
                    continue;
                }
                // 根据ruleId或alertType获取或创建规则实体
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
                ruleCache.invalidateRules();
            }
        }
        return listRules();
    }

    /**
     * 列出告警事件列表
     *
     * @param limit 返回数量限制（默认100，最大500）
     * @return 告警事件列表响应
     */
    @Transactional(readOnly = true)
    public AlertEventListResponse listEvents(Integer limit) {
        int safeLimit = Math.max(1, Math.min(500, limit == null ? 100 : limit));
        return new AlertEventListResponse(eventMapper.selectLatestOrderByCreatedAtDesc(safeLimit)
            .stream()
            .map(this::eventView)
            .toList());
    }

    /**
     * 触发敏感数据告警事件
     *
     * @param sensitiveEvent 敏感数据事件实体
     */
    @Transactional
    public void emitSensitiveEvent(SensitiveEventEntity sensitiveEvent) {
        if (sensitiveEvent == null) {
            return;
        }
        // 根据动作类型确定严重级别：BLOCK为CRITICAL，MASK为WARNING
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

    /**
     * 触发Provider Token预算超限告警
     *
     * @param providerId Provider标识
     * @param traceId 追踪ID
     * @param agentId Agent标识
     * @param sessionId 会话ID
     * @param scope 预算范围（daily/weekly/monthly）
     * @param usedTokens 已使用Token数
     * @param limitTokens 限制Token数
     */
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

    /**
     * 触发Provider Token预算预警
     *
     * @param providerId Provider标识
     * @param traceId 追踪ID
     * @param agentId Agent标识
     * @param sessionId 会话ID
     * @param scope 预算范围（daily/weekly/monthly）
     * @param usedTokens 已使用Token数
     * @param limitTokens 限制Token数
     * @param usagePercent 使用百分比
     */
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

    /**
     * 触发AI调用失败告警
     *
     * @param user 用户信息
     * @param ctx 管道上下文
     * @param errorMessage 错误信息
     */
    @Transactional
    public void emitCallError(AuthUser user, PipelineContext ctx, String errorMessage) {
        AuthUser owner = user == null ? AuthUser.DEFAULT : user;
        emit(AlertType.CALL_ERROR, AlertSeverity.WARNING, ctx == null ? null : ctx.getTraceId(), owner.userId(),
            username(owner), ctx == null ? null : ctx.getAgentId(), ctx == null ? null : ctx.getSessionId(),
            providerId(ctx),
            "AI 调用失败",
            truncate(errorMessage, 1000),
            "按 TraceID 查看失败 Span，确认模型、工具或存储依赖是否异常。");
        // 额外检查错误率是否超过阈值
        maybeEmitErrorRate(owner, ctx);
    }

    /**
     * 检查并触发错误率告警
     *
     * <p>当最近一段时间内错误率超过配置的阈值时触发告警。</p>
     *
     * @param user 用户信息
     * @param ctx 管道上下文
     */
    private void maybeEmitErrorRate(AuthUser user, PipelineContext ctx) {
        AlertRuleEntity rule = activeRule(AlertType.ERROR_RATE);
        if (rule == null || rule.getThresholdPercent() == null) {
            return;
        }
        // 获取统计窗口（默认5分钟）
        int window = rule.getWindowMinutes() == null || rule.getWindowMinutes() <= 0 ? 5 : rule.getWindowMinutes();
        Instant since = Instant.now(clock).minus(window, ChronoUnit.MINUTES);

        // 查询窗口内的总调用数和错误数
        long total = usageMapper.countByUsageSourceAndCreatedAtGreaterThanEqual(TokenUsageSource.PROVIDER, since);
        if (total == 0) {
            return;
        }
        long errors = usageMapper.countByUsageSourceAndStatusAndCreatedAtGreaterThanEqual(
            TokenUsageSource.PROVIDER, "ERROR", since);

        // 计算错误率
        double rate = errors * 100.0 / total;
        if (rate < rule.getThresholdPercent()) {
            return;
        }

        // 触发错误率告警
        emitWithRule(rule, user == null ? AuthUser.DEFAULT : user, ctx,
            "AI 调用错误率超阈值",
            "最近 " + window + " 分钟错误率 " + String.format("%.2f%%", rate)
                + "，阈值 " + rule.getThresholdPercent() + "%。",
            "优先查看最近错误 Trace，确认底层模型或工具依赖状态。");
    }

    /**
     * 触发告警（通用方法）
     *
     * <p>检查告警规则是否启用，构建告警事件，处理静默逻辑并发送通知。</p>
     *
     * @param type 告警类型
     * @param fallbackSeverity 默认严重级别（当规则未配置时使用）
     * @param traceId 追踪ID
     * @param userId 用户ID
     * @param username 用户名
     * @param agentId Agent标识
     * @param sessionId 会话ID
     * @param providerId Provider标识
     * @param title 告警标题
     * @param message 告警消息
     * @param suggestion 处理建议
     */
    private void emit(AlertType type, AlertSeverity fallbackSeverity, String traceId, String userId, String username,
                      String agentId, String sessionId, String providerId, String title, String message,
                      String suggestion) {
        // 获取激活的告警规则
        AlertRuleEntity rule = activeRule(type);
        if (rule == null) {
            return;
        }
        // 构建基础告警事件
        AlertEventEntity event = baseEvent(type, rule.getSeverity() == null ? fallbackSeverity : rule.getSeverity(),
            traceId, userId, username, agentId, sessionId, providerId, title, message, suggestion);
        // 完成并保存告警
        finishAndSave(rule, event);
    }

    /**
     * 使用指定规则触发告警
     *
     * @param rule 告警规则
     * @param user 用户信息
     * @param ctx 管道上下文
     * @param title 告警标题
     * @param message 告警消息
     * @param suggestion 处理建议
     */
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

    /**
     * 完成告警处理并保存
     *
     * <p>检查告警是否处于静默期，决定是保存为静默状态还是发送通知。</p>
     *
     * @param rule 告警规则
     * @param event 告警事件
     */
    private void finishAndSave(AlertRuleEntity rule, AlertEventEntity event) {
        Instant now = Instant.now(clock);
        if (isSilenced(rule, event, now)) {
            // 静默期内，记录为静默状态
            Instant since = now.minus(rule.getQuietMinutes(), ChronoUnit.MINUTES);
            int suppressedCount = boundedCount(silencedCount(rule, event, since) + 1);
            event.setStatus(AlertStatus.SILENCED);
            event.setSuppressedCount(suppressedCount);
            eventMapper.upsertById(event);
            // 检查是否需要升级
            maybeEscalate(rule, event, since, suppressedCount);
            return;
        }
        // 发送通知并保存
        sendAndSave(rule, event);
    }

    /**
     * 发送告警通知并保存到数据库
     *
     * @param rule 告警规则
     * @param event 告警事件
     */
    private void sendAndSave(AlertRuleEntity rule, AlertEventEntity event) {
        FeishuWebhookClient.SendResult result = feishuWebhookClient.send(event);
        event.setStatus(result.status());
        event.setFailureReason(truncate(result.failureReason(), 1000));
        event.setProviderResponse(truncate(result.providerResponse(), 1000));
        eventMapper.upsertById(event);
    }

    /**
     * 检查是否需要升级告警
     *
     * <p>当静默期内累计告警次数达到升级阈值时，升级为CRITICAL级别重新发送。</p>
     *
     * @param rule 告警规则
     * @param silencedEvent 静默的告警事件
     * @param since 静默期起始时间
     * @param suppressedCount 静默期内累计次数
     */
    private void maybeEscalate(AlertRuleEntity rule, AlertEventEntity silencedEvent, Instant since, int suppressedCount) {
        // 检查是否启用升级且达到升级阈值
        if (!rule.isEscalationEnabled() || suppressedCount < rule.getEscalationThreshold()) {
            return;
        }
        // 检查该规则在静默期内是否已升级过
        if (escalationExists(rule, silencedEvent, since)) {
            return;
        }

        // 创建升级告警事件（CRITICAL级别）
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

    /**
     * 判断告警是否处于静默期
     *
     * @param rule 告警规则
     * @param event 告警事件
     * @param now 当前时间
     * @return true表示处于静默期，不发送通知
     */
    private boolean isSilenced(AlertRuleEntity rule, AlertEventEntity event, Instant now) {
        // 静默时间为0表示不静默
        if (rule.getQuietMinutes() <= 0) {
            return false;
        }
        Instant since = now.minus(rule.getQuietMinutes(), ChronoUnit.MINUTES);
        // Provider预算类告警按providerId区分静默
        if (isProviderBudgetType(rule.getAlertType())) {
            return eventMapper.existsByAlertTypeAndProviderIdAndStatusAndCreatedAtGreaterThanEqual(
                rule.getAlertType(), event.getProviderId(), AlertStatus.SENT, since);
        }
        // 其他告警按类型区分静默
        return eventMapper.existsByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(
            rule.getAlertType(), AlertStatus.SENT, since);
    }

    /**
     * 获取激活的告警规则
     *
     * @param type 告警类型
     * @return 激活的告警规则，未找到返回null
     */
    private AlertRuleEntity activeRule(AlertType type) {
        ensureDefaultRules();
        return ruleCache.ruleByType(type, () -> ruleMapper.selectOneByAlertType(type))
            .filter(AlertRuleEntity::isEnabled)
            .orElse(null);
    }

    /**
     * 构建基础告警事件实体
     *
     * @param type 告警类型
     * @param severity 严重级别
     * @param traceId 追踪ID
     * @param userId 用户ID
     * @param username 用户名
     * @param agentId Agent标识
     * @param sessionId 会话ID
     * @param providerId Provider标识
     * @param title 告警标题
     * @param message 告警消息
     * @param suggestion 处理建议
     * @return 告警事件实体
     */
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

    /**
     * 确保默认告警规则存在
     *
     * <p>遍历所有告警类型：
     * <ul>
     *   <li>有效类型但不存在：创建默认规则</li>
     *   <li>有效类型且已存在：跳过</li>
     * </ul>
     * </p>
     */
    private void ensureDefaultRules() {
        boolean changed = false;
        for (AlertType type : AlertType.values()) {
            if (ruleCache.ruleByType(type, () -> ruleMapper.selectOneByAlertType(type)).isPresent()) {
                // 规则已存在，跳过
                continue;
            }
            // 创建默认规则
            ruleMapper.upsertById(defaultRule(type));
            changed = true;
        }
        if (changed) {
            ruleCache.invalidateRules();
        }
    }

    /**
     * 创建默认告警规则
     *
     * @param type 告警类型
     * @return 默认规则实体
     */
    private AlertRuleEntity defaultRule(AlertType type) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setAlertType(type);
        rule.setRuleName(defaultName(type));
        rule.setEnabled(true);
        rule.setSeverity(defaultSeverity(type));
        rule.setQuietMinutes(defaultQuietMinutes(type));
        rule.setEscalationEnabled(true);
        rule.setEscalationThreshold(3);
        // 错误率告警需要额外配置阈值和窗口
        if (type == AlertType.ERROR_RATE) {
            rule.setThresholdPercent(20.0);
            rule.setWindowMinutes(5);
        }
        return rule;
    }

    /**
     * 获取告警类型的默认名称
     *
     * @param type 告警类型
     * @return 中文名称
     */
    private String defaultName(AlertType type) {
        return switch (type) {
            case CALL_ERROR -> "调用错误";
            case ERROR_RATE -> "错误率阈值";
            case PROVIDER_TOKEN_BUDGET_EXCEEDED -> "Provider Token 预算超限";
            case PROVIDER_TOKEN_BUDGET_WARNING -> "Provider Token 预算预警";
            case SENSITIVE_DATA -> "敏感数据事件";
        };
    }

    /**
     * 获取告警类型的默认严重级别
     *
     * @param type 告警类型
     * @return 严重级别
     */
    private AlertSeverity defaultSeverity(AlertType type) {
        return switch (type) {
            case PROVIDER_TOKEN_BUDGET_EXCEEDED -> AlertSeverity.CRITICAL;
            case SENSITIVE_DATA, CALL_ERROR, ERROR_RATE, PROVIDER_TOKEN_BUDGET_WARNING ->
                AlertSeverity.WARNING;
        };
    }

    /**
     * 获取告警类型的默认静默时间（分钟）
     *
     * @param type 告警类型
     * @return 静默时间（分钟）
     */
    private int defaultQuietMinutes(AlertType type) {
        return switch (type) {
            case PROVIDER_TOKEN_BUDGET_WARNING -> 120;
            case PROVIDER_TOKEN_BUDGET_EXCEEDED -> 60;
            case CALL_ERROR, ERROR_RATE -> 15;
            case SENSITIVE_DATA -> 30;
        };
    }

    /**
     * 转换告警规则实体为视图对象
     *
     * @param rule 告警规则实体
     * @return 视图对象
     */
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

    /**
     * 转换告警事件实体为视图对象
     *
     * @param event 告警事件实体
     * @return 视图对象
     */
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

    /**
     * 规范化正数（小于等于0返回null）
     *
     * @param value 输入值
     * @return 规范化后的值
     */
    private Integer normalizePositive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    /**
     * 规范化升级阈值（默认3，最大1000）
     *
     * @param value 输入值
     * @return 规范化后的值
     */
    private int normalizeEscalationThreshold(Integer value) {
        if (value == null || value <= 0) {
            return 3;
        }
        return Math.min(1000, value);
    }

    /**
     * 限制计数值在Integer范围内
     *
     * @param value 输入值
     * @return 限制后的值
     */
    private int boundedCount(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /**
     * 统计静默期内的静默告警数量
     *
     * @param rule 告警规则
     * @param event 告警事件
     * @param since 静默期起始时间
     * @return 静默告警数量
     */
    private long silencedCount(AlertRuleEntity rule, AlertEventEntity event, Instant since) {
        if (isProviderBudgetType(rule.getAlertType())) {
            return eventMapper.countByAlertTypeAndProviderIdAndStatusAndCreatedAtGreaterThanEqual(
                rule.getAlertType(), event.getProviderId(), AlertStatus.SILENCED, since);
        }
        return eventMapper.countByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(
            rule.getAlertType(), AlertStatus.SILENCED, since);
    }

    /**
     * 检查静默期内是否已存在升级告警
     *
     * @param rule 告警规则
     * @param event 告警事件
     * @param since 静默期起始时间
     * @return true表示已存在升级告警
     */
    private boolean escalationExists(AlertRuleEntity rule, AlertEventEntity event, Instant since) {
        if (isProviderBudgetType(rule.getAlertType())) {
            return eventMapper.existsByAlertTypeAndProviderIdAndEscalatedTrueAndCreatedAtGreaterThanEqual(
                rule.getAlertType(), event.getProviderId(), since);
        }
        return eventMapper.existsByAlertTypeAndEscalatedTrueAndCreatedAtGreaterThanEqual(rule.getAlertType(), since);
    }

    /**
     * 判断是否为Provider预算类型告警
     *
     * @param type 告警类型
     * @return true表示是Provider预算类型
     */
    private boolean isProviderBudgetType(AlertType type) {
        return type == AlertType.PROVIDER_TOKEN_BUDGET_WARNING
            || type == AlertType.PROVIDER_TOKEN_BUDGET_EXCEEDED;
    }

    /**
     * 从管道上下文中提取Provider ID
     *
     * @param ctx 管道上下文
     * @return Provider ID
     */
    private String providerId(PipelineContext ctx) {
        if (ctx == null) {
            return null;
        }
        // 优先从已解析的模型中获取
        if (ctx.getResolvedModel() != null && ctx.getResolvedModel().providerId() != null
            && !ctx.getResolvedModel().providerId().isBlank()) {
            return ctx.getResolvedModel().providerId();
        }
        // 从modelId中提取（格式：provider:model）
        String modelId = ctx.getModelId();
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        int separator = modelId.indexOf(':');
        return separator > 0 ? modelId.substring(0, separator) : modelId;
    }

    /**
     * 获取用户名（处理未认证用户）
     *
     * @param user 用户信息
     * @return 用户名
     */
    private String username(AuthUser user) {
        if (user == null || !user.authenticated()) {
            return "default";
        }
        return user.username();
    }

    /**
     * 获取默认值（空值时返回fallback）
     *
     * @param value 输入值
     * @param fallback 默认值
     * @return 结果值
     */
    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 截断字符串到最大长度
     *
     * @param value 输入字符串
     * @param maxLength 最大长度
     * @return 截断后的字符串
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
