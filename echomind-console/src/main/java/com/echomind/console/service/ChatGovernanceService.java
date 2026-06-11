package com.echomind.console.service;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.console.alerts.AlertService;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.budget.ProviderTokenBudgetService;
import com.echomind.console.quota.TokenQuotaExceededException;
import com.echomind.console.quota.TokenQuotaService;
import com.echomind.console.sensitive.SensitiveDataService;
import com.echomind.console.usage.AiCallUsageEntity;
import com.echomind.console.usage.AiCallUsageService;
import com.echomind.common.observability.EchoMindTrace;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 聊天治理服务
 *
 * <p>作为聊天请求的统一治理入口，负责在请求处理的各个阶段进行合规检查和记录。
 * 核心职责包括：</p>
 *
 * <ul>
 *   <li><strong>用户配额管理</strong>：检查用户Token配额、预留额度、释放额度</li>
 *   <li><strong>敏感数据治理</strong>：检测请求/响应中的敏感内容，支持脱敏或阻断</li>
 *   <li><strong>Token使用记录</strong>：记录每次AI调用的Token消耗，持久化到数据库</li>
 *   <li><strong>告警触发</strong>：当调用失败时触发告警通知</li>
 *   <li><strong>Provider预算管理</strong>：管理模型服务提供商的Token预算</li>
 * </ul>
 *
 * <p>治理流程在聊天链路中的位置：</p>
 * <pre>
 * 前端请求 → Controller → ChatApplicationService
 *                              │
 *                              ▼
 *                    ┌─────────────────┐
 *                    │ ChatGovernanceService │
 *                    │  ├─ inspectRequest()  │ ← 请求侧治理（配额+脱敏）
 *                    │  ├─ reserveQuota()   │ ← Token预留
 *                    │  └─ recordUsage()    │ ← 使用记录
 *                    └─────────────────┘
 *                              │
 *                              ▼
 *                    RabbitMQ → Agent执行管线
 * </pre>
 */
@Service
@Slf4j
public class ChatGovernanceService {

    /** AI调用使用量服务（负责Token使用记录的持久化和额度管理） */
    private final AiCallUsageService usageService;

    /** Token配额服务（负责用户级别的Token配额检查和预留） */
    private final TokenQuotaService quotaService;

    /** 敏感数据服务（负责请求/响应的敏感内容检测和脱敏处理） */
    private final SensitiveDataService sensitiveDataService;

    /** 告警服务（负责调用错误时的告警触发和通知） */
    private final AlertService alertService;

    /**
     * Provider预算服务（负责Provider级别的Token预算管理）
     * 使用ObjectProvider实现懒加载，允许服务不存在时优雅降级
     */
    private final ObjectProvider<ProviderTokenBudgetService> providerBudgetService;

    /**
     * 默认构造函数（用于测试或ProviderBudgetService不可用时）
     */
    public ChatGovernanceService(AiCallUsageService usageService,
                                 TokenQuotaService quotaService,
                                 SensitiveDataService sensitiveDataService,
                                 AlertService alertService) {
        this(usageService, quotaService, sensitiveDataService, alertService, null);
    }

    /**
     * 完整构造函数（生产环境使用）
     *
     * @param usageService         AI调用使用量服务
     * @param quotaService         Token配额服务
     * @param sensitiveDataService 敏感数据服务
     * @param alertService         告警服务
     * @param providerBudgetService Provider预算服务（可选）
     */
    @Autowired
    public ChatGovernanceService(AiCallUsageService usageService,
                                 TokenQuotaService quotaService,
                                 SensitiveDataService sensitiveDataService,
                                 AlertService alertService,
                                 ObjectProvider<ProviderTokenBudgetService> providerBudgetService) {
        this.usageService = usageService;
        this.quotaService = quotaService;
        this.sensitiveDataService = sensitiveDataService;
        this.alertService = alertService;
        this.providerBudgetService = providerBudgetService;
    }

    /**
     * 检查请求合规性（请求侧治理入口）
     *
     * <p>在请求入队前执行两项关键检查，确保请求合法合规：</p>
     *
     * <ol>
     *   <li><strong>用户Token配额检查</strong>：验证用户剩余Token是否充足，
     *       超限时抛出TokenQuotaExceededException</li>
     *   <li><strong>请求内容敏感数据检测</strong>：检测用户消息中是否包含敏感内容，
     *       命中阻断规则时直接短路返回替代回复，否则返回脱敏后的消息</li>
     * </ol>
     *
     * @param span       追踪Span（用于记录治理决策）
     * @param authUser   认证用户信息
     * @param agentId    Agent标识
     * @param sessionId  会话ID
     * @param message    用户请求消息
     * @return RequestInspection 包含脱敏后的消息或短路回复
     * @throws TokenQuotaExceededException 当用户Token配额超限时抛出
     */
    public RequestInspection inspectRequest(Span span, AuthUser authUser, String agentId, String sessionId,
                                            String message) {
        // Step 1: 用户Token配额检查 - 检查用户当前Token配额是否允许发起请求
        //         如果配额不足，直接抛出异常阻止请求进入队列
        assertQuotaAllowed(span, authUser, agentId, sessionId);

        // Step 2: 请求内容敏感数据检测 - 检测消息中是否包含敏感内容
        //         SensitiveDataService会根据配置的规则进行检测和处理
        SensitiveDataService.GovernedText governed = sensitiveDataService.inspectRequest(
            authUser,
            EchoMindTrace.traceId(span),
            agentId,
            sessionId,
            message
        );

        // Step 3: 判断是否需要短路返回
        //         如果命中BLOCK规则，直接返回替代回复，不进入Agent执行管线
        if (governed.blocked()) {
            return RequestInspection.shortCircuit(governed.text());
        }

        // Step 4: 正常通过，返回脱敏后的消息继续处理
        return RequestInspection.continueWith(governed.text());
    }

    /**
     * 预留用户Token配额
     *
     * <p>在请求入队前预先冻结预估的Token数量，防止并发场景下超配额。
     * 预留的额度会在请求完成后根据实际消耗进行结算或释放。</p>
     *
     * @param authUser        认证用户
     * @param requestId       请求ID（用于关联预留记录）
     * @param estimatedTokens 预估Token消耗数量
     * @return 预留ID列表（用于后续结算或释放）
     */
    public List<String> reserveUserQuota(AuthUser authUser, String requestId, long estimatedTokens) {
        return quotaService.reserveUsage(authUser, requestId, estimatedTokens);
    }

    /**
     * 预留Provider Token预算
     *
     * <p>在请求入队前预先冻结Provider级别的Token预算，防止单个Provider被过度使用。
     * 如果ProviderBudgetService不可用或providerId为空，返回空列表（优雅降级）。</p>
     *
     * @param providerId      Provider标识
     * @param requestId       请求ID
     * @param estimatedTokens 预估Token消耗数量
     * @return 预留ID列表（用于后续结算或释放）
     */
    public List<String> reserveProviderBudget(String providerId, String requestId, long estimatedTokens) {
        // 优雅降级：如果服务未配置或providerId无效，跳过预算预留
        ProviderTokenBudgetService service = providerBudgetService == null ? null : providerBudgetService.getIfAvailable();
        if (service == null || providerId == null || providerId.isBlank()) {
            return List.of();
        }
        return service.reserveProviderBudget(providerId, requestId, estimatedTokens);
    }

    /**
     * 释放预留的Token额度
     *
     * <p>当请求失败或取消时，释放之前预留的Token额度，使其重新可用。</p>
     *
     * @param reservationIds 预留ID列表
     */
    public void releaseReservations(List<String> reservationIds) {
        usageService.releaseReservations(reservationIds);
    }

    /**
     * 检查响应合规性（响应侧治理）
     *
     * <p>在Agent返回响应后，对响应内容进行敏感数据检测和脱敏处理，
     * 确保返回给用户的内容符合合规要求。</p>
     *
     * @param authUser 认证用户
     * @param ctx      管线上下文（包含最终响应）
     */
    public void inspectResponse(AuthUser authUser, PipelineContext ctx) {
        // 空值检查：如果上下文或响应为空，无需处理
        if (ctx == null || ctx.getFinalResponse() == null || ctx.getFinalResponse().isBlank()) {
            return;
        }

        // 检测并处理响应中的敏感数据，将脱敏结果更新回上下文
        ctx.setFinalResponse(inspectResponseText(authUser, ctx.getTraceId(), ctx.getAgentId(), ctx.getSessionId(),
            ctx.getFinalResponse()));
    }

    /**
     * 检测响应文本中的敏感数据
     *
     * <p>调用SensitiveDataService对响应文本进行脱敏处理，确保返回内容合规。</p>
     *
     * @param authUser  认证用户
     * @param traceId   追踪ID（用于记录敏感事件）
     * @param agentId   Agent标识
     * @param sessionId 会话ID
     * @param text      原始响应文本
     * @return 脱敏后的文本
     */
    public String inspectResponseText(AuthUser authUser, String traceId, String agentId, String sessionId, String text) {
        return sensitiveDataService.inspectResponse(authUser, traceId, agentId, sessionId, text).text();
    }

    /**
     * 如果调用失败则触发告警
     *
     * <p>检查PipelineContext是否标记为失败，如果是则触发告警。
     * 常用于调用完成后的统一告警判断。</p>
     *
     * @param authUser 认证用户
     * @param ctx      管线上下文
     * @return true表示已触发告警，false表示调用未失败
     */
    public boolean emitCallErrorIfFailed(AuthUser authUser, PipelineContext ctx) {
        if (!isFailed(ctx)) {
            return false;
        }
        emitCallError(authUser, ctx, errorMessage(ctx));
        return true;
    }

    /**
     * 触发调用错误告警
     *
     * <p>将错误消息规范化后发送给AlertService，由AlertService负责具体的告警推送
     * （如飞书Webhook、邮件等）。</p>
     *
     * @param authUser     认证用户
     * @param ctx          管线上下文（包含错误详情）
     * @param errorMessage 原始错误消息
     */
    public void emitCallError(AuthUser authUser, PipelineContext ctx, String errorMessage) {
        alertService.emitCallError(authUser, ctx, normalizeErrorMessage(errorMessage));
    }

    /**
     * 记录成功的Token使用并触发相关告警
     *
     * <p>核心流程：</p>
     * <ol>
     *   <li><strong>适用性检查</strong>：判断是否需要记录（某些内部调用可能不需要记录）</li>
     *   <li><strong>持久化记录</strong>：调用AiCallUsageService将Token使用写入数据库</li>
     *   <li><strong>追踪标记</strong>：将使用数据记录到OpenTelemetry Span中</li>
     * </ol>
     *
     * @param span         追踪Span（用于记录使用数据）
     * @param operation    操作名称（如 echomind.chat.stream.consume）
     * @param authUser     认证用户
     * @param ctx          管线上下文（包含Token使用数据）
     * @param startedNanos 调用开始时间（纳秒，用于计算耗时）
     * @return 持久化的使用记录实体，如果模型未返回有效数据则返回null
     */
    public AiCallUsageEntity recordSuccessAndWarnings(Span span, String operation, AuthUser authUser,
                                                      PipelineContext ctx, long startedNanos) {
        // Step 1: 检查是否需要记录 - 通过上下文标记判断是否跳过记录
        if (modelUsageNotApplicable(ctx)) {
            usageService.releaseReservations(ctx);
            return null;
        }

        // Step 2: 记录成功的Token使用 - 持久化到MySQL
        AiCallUsageEntity usage = usageService.recordSuccess(operation, authUser, ctx, startedNanos);

        // Step 3: 将使用数据记录到追踪Span - 便于Jaeger等工具查询
        tagUsage(span, usage);

        return usage;
    }

    /**
     * 记录流式调用的Token使用（流式聊天专用）
     *
     * <p>处理流式聊天场景下的Token使用记录，支持成功和失败两种情况。
     * 与recordSuccessAndWarnings的区别在于：
     * <ul>
     *   <li>专门处理流式返回的token流</li>
     *   <li>支持错误场景的记录</li>
     *   <li>包含完善的异常处理和降级逻辑</li>
     * </ul>
     * </p>
     *
     * @param span         追踪Span
     * @param operation    操作名称（如 echomind.chat.stream.consume）
     * @param authUser     认证用户
     * @param usageContext 管线上下文（包含Token使用数据）
     * @param startedNanos 调用开始时间（纳秒）
     * @param error        是否为错误场景
     * @param errorMessage 错误消息（仅error=true时有意义）
     */
    public void recordStreamUsage(Span span, String operation, AuthUser authUser, PipelineContext usageContext,
                                  long startedNanos, boolean error, String errorMessage) {
        // Step 1: 非错误场景下检查是否需要记录
        if (!error && modelUsageNotApplicable(usageContext)) {
            usageService.releaseReservations(usageContext);
            return;
        }

        try {
            // Step 2: 根据是否错误选择记录方法
            AiCallUsageEntity usage = error
                ? usageService.recordError(operation, authUser, usageContext, startedNanos,
                    normalizeErrorMessage(errorMessage))
                : usageService.recordSuccess(operation, authUser, usageContext, startedNanos);

            // Step 3: 将使用数据记录到追踪Span
            tagUsage(span, usage);

            // Step 4: 触发错误告警（如果有错误）
            if (error) {
                emitCallError(authUser, usageContext, errorMessage);
            } else {
                // 成功场景下仍需检查上下文是否标记失败
                emitCallErrorIfFailed(authUser, usageContext);
            }
        } catch (TokenQuotaExceededException e) {
            // Case 1: Token配额超限异常 - 记录到Span并重新抛出
            //         此异常需要向上传递，让上层处理配额不足的情况
            EchoMindTrace.recordException(span, e);
            throw e;
        } catch (RuntimeException e) {
            // Case 2: 其他运行时异常 - 记录到Span，尝试触发告警，记录警告日志
            //         此异常不向上传递，避免影响正常的聊天流程
            EchoMindTrace.recordException(span, e);
            if (error || isFailed(usageContext)) {
                emitCallError(authUser, usageContext, error ? errorMessage : errorMessage(usageContext));
            }
            log.warn("Failed to record stream token usage trace={} user={} session={}: {}",
                usageContext == null ? "" : usageContext.getTraceId(),
                authUser == null ? "" : authUser.userId(),
                usageContext == null ? "" : usageContext.getSessionId(),
                e.getMessage());
        }
    }

    /**
     * 判断调用是否失败
     *
     * <p>通过检查PipelineContext的失败标记来判断调用是否成功。</p>
     *
     * @param ctx 管线上下文
     * @return true表示调用失败
     */
    public boolean isFailed(PipelineContext ctx) {
        return ctx != null && ctx.hasFailed();
    }

    /**
     * 获取错误消息
     *
     * <p>优先级策略：失败原因 > 最终响应 > 默认消息</p>
     *
     * @param ctx 管线上下文
     * @return 规范化的错误消息
     */
    public String errorMessage(PipelineContext ctx) {
        if (ctx == null) {
            return "模型调用失败";
        }
        // 优先使用明确的失败原因
        if (ctx.hasFailed()) {
            return ctx.effectiveFailureReason();
        }
        // 如果没有明确失败，但响应为空，也视为失败
        if (ctx.getFinalResponse() == null || ctx.getFinalResponse().isBlank()) {
            return "模型调用失败";
        }
        // 最后使用响应内容作为错误消息
        return ctx.getFinalResponse();
    }

    /**
     * 检查用户Token配额（私有方法）
     *
     * <p>调用TokenQuotaService验证用户当前配额是否充足。
     * 如果配额不足，抛出TokenQuotaExceededException。</p>
     *
     * @param span     追踪Span
     * @param authUser 认证用户
     * @param agentId  Agent标识
     * @param sessionId 会话ID
     * @throws TokenQuotaExceededException 当配额超限时抛出
     */
    private void assertQuotaAllowed(Span span, AuthUser authUser, String agentId, String sessionId) {
        quotaService.assertAllowed(authUser);
    }

    /**
     * 将Token使用数据记录到追踪Span
     *
     * <p>设置以下Span属性，便于通过Jaeger等观测工具进行查询和分析：</p>
     *
     * <table>
     *   <tr><th>属性名</th><th>说明</th><th>示例值</th></tr>
     *   <tr><td>echomind.user_id</td><td>用户ID</td><td>user123</td></tr>
     *   <tr><td>echomind.account_type</td><td>账户类型</td><td>client/admin</td></tr>
     *   <tr><td>echomind.username</td><td>用户名</td><td>张三</td></tr>
     *   <tr><td>echomind.agent_id</td><td>Agent标识</td><td>default</td></tr>
     *   <tr><td>echomind.session_id</td><td>会话ID</td><td>abc-123</td></tr>
     *   <tr><td>echomind.model_id</td><td>模型ID</td><td>deepseek:deepseek-v4-flash</td></tr>
     *   <tr><td>echomind.provider_id</td><td>Provider标识</td><td>deepseek</td></tr>
     *   <tr><td>echomind.prompt_tokens</td><td>提示词Token数</td><td>100</td></tr>
     *   <tr><td>echomind.completion_tokens</td><td>回复Token数</td><td>200</td></tr>
     *   <tr><td>echomind.total_tokens</td><td>总Token数</td><td>300</td></tr>
     *   <tr><td>echomind.usage_source</td><td>使用来源</td><td>PROVIDER/ESTIMATED</td></tr>
     * </table>
     *
     * @param span  追踪Span
     * @param usage Token使用记录实体
     */
    private void tagUsage(Span span, AiCallUsageEntity usage) {
        if (span == null || usage == null) {
            return;
        }
        span.setAttribute("echomind.user_id", safe(usage.getUserId()));
        span.setAttribute("echomind.account_type", usage.getAccountType());
        span.setAttribute("echomind.username", safe(usage.getUsername()));
        span.setAttribute("echomind.agent_id", safe(usage.getAgentId()));
        span.setAttribute("echomind.session_id", safe(usage.getSessionId()));
        span.setAttribute("echomind.model_id", safe(usage.getModelId()));
        span.setAttribute("echomind.provider_id", safe(usage.getProviderId()));
        span.setAttribute("echomind.prompt_tokens", usage.getPromptTokens());
        span.setAttribute("echomind.completion_tokens", usage.getCompletionTokens());
        span.setAttribute("echomind.total_tokens", usage.getTotalTokens());
        span.setAttribute("echomind.usage_source", usage.getUsageSource().name());
    }

    /**
     * 判断是否不需要记录模型使用
     *
     * <p>通过检查上下文中的特殊标记ATTR_MODEL_USAGE_NOT_APPLICABLE来判断。
     * 此标记通常由内部调用设置，表示该次调用不需要记录Token使用（如测试、预热等）。</p>
     *
     * @param ctx 管线上下文
     * @return true表示不需要记录
     */
    private boolean modelUsageNotApplicable(PipelineContext ctx) {
        return ctx != null
            && Boolean.TRUE.equals(ctx.getAttributes().get(PipelineContext.ATTR_MODEL_USAGE_NOT_APPLICABLE));
    }

    /**
     * 规范化错误消息
     *
     * <p>处理步骤：
     * <ol>
     *   <li>去除前后空格</li>
     *   <li>移除可能的[Error]前缀（某些错误消息会自动添加此前缀）</li>
     *   <li>如果消息为空或空白，返回默认消息"模型调用失败"</li>
     * </ol>
     * </p>
     *
     * @param value 原始错误消息
     * @return 规范化后的错误消息
     */
    private String normalizeErrorMessage(String value) {
        if (value == null || value.isBlank()) {
            return "模型调用失败";
        }
        String trimmed = value.trim();
        return trimmed.startsWith("[Error]") ? trimmed.substring("[Error]".length()).trim() : trimmed;
    }

    /**
     * 安全获取字符串（null转为空字符串）
     *
     * <p>防止NullPointerException，用于设置Span属性等场景。</p>
     *
     * @param value 输入值
     * @return 安全的字符串（非null）
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 请求检查结果记录
     *
     * <p>封装请求侧治理的检查结果，包含两种状态：
     * <ul>
     *   <li>继续处理：包含脱敏后的消息</li>
     *   <li>短路返回：包含替代回复（敏感内容命中BLOCK规则时）</li>
     * </ul>
     * </p>
     */
    public record RequestInspection(String governedMessage, String shortCircuitReply) {

        /**
         * 创建继续处理的结果
         *
         * @param governedMessage 脱敏后的消息
         * @return RequestInspection
         */
        public static RequestInspection continueWith(String governedMessage) {
            return new RequestInspection(governedMessage, null);
        }

        /**
         * 创建短路返回的结果
         *
         * @param reply 替代回复内容
         * @return RequestInspection
         */
        public static RequestInspection shortCircuit(String reply) {
            return new RequestInspection(null, reply == null ? "" : reply);
        }

        /**
         * 判断是否需要短路返回
         *
         * @return true表示需要短路返回
         */
        public boolean shortCircuited() {
            return shortCircuitReply != null;
        }
    }
}