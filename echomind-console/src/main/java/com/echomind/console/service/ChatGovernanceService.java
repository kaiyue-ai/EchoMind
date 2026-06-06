package com.echomind.console.service;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.console.alerts.AlertService;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.quota.TokenQuotaExceededException;
import com.echomind.console.quota.TokenQuotaService;
import com.echomind.console.sensitive.SensitiveDataService;
import com.echomind.console.usage.AiCallUsageEntity;
import com.echomind.console.usage.AiCallUsageService;
import com.echomind.common.observability.EchoMindTrace;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 聊天治理服务
 * 
 * <p>负责聊天请求的治理管控，包括：
 * <ul>
 *   <li>用户配额检查</li>
 *   <li>敏感数据检测与处理</li>
 *   <li>Token使用记录</li>
 *   <li>调用错误告警</li>
 * </ul>
 * </p>
 * 
 * <p>作为聊天请求的治理入口，在请求处理的各个阶段进行合规检查和记录。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatGovernanceService {

    /** AI调用使用量服务（用于记录Token使用） */
    private final AiCallUsageService usageService;
    
    /** Token配额服务（用于检查用户配额） */
    private final TokenQuotaService quotaService;
    
    /** 敏感数据服务（用于检测敏感内容） */
    private final SensitiveDataService sensitiveDataService;
    
    /** 告警服务（用于触发告警） */
    private final AlertService alertService;

    /**
     * 检查请求合规性
     * 
     * <p>执行以下检查：
     * <ol>
     *   <li>用户Token配额检查</li>
     *   <li>请求内容敏感数据检测</li>
     * </ol>
     * </p>
     * 
     * @param span     追踪Span
     * @param authUser 认证用户
     * @param agentId  Agent标识
     * @param sessionId 会话ID
     * @param message  请求消息
     * @return 请求治理结果，包含脱敏后的消息或请求侧短路回复
     * @throws TokenQuotaExceededException 当用户Token配额超限时抛出
     */
    public RequestInspection inspectRequest(Span span, AuthUser authUser, String agentId, String sessionId,
                                            String message) {
        // Step 1: 检查用户Token配额
        assertQuotaAllowed(span, authUser, agentId, sessionId);
        
        // Step 2: 检测并处理请求中的敏感数据
        SensitiveDataService.GovernedText governed = sensitiveDataService.inspectRequest(
            authUser,
            EchoMindTrace.traceId(span),
            agentId,
            sessionId,
            message
        );
        if (governed.blocked()) {
            return RequestInspection.shortCircuit(governed.text());
        }
        return RequestInspection.continueWith(governed.text());
    }

    /**
     * 检查响应合规性
     * 
     * <p>检测响应内容中的敏感数据并进行脱敏处理。</p>
     * 
     * @param authUser 认证用户
     * @param ctx      管线上下文
     */
    public void inspectResponse(AuthUser authUser, PipelineContext ctx) {
        if (ctx == null || ctx.getFinalResponse() == null || ctx.getFinalResponse().isBlank()) {
            return;
        }
        // 检测并处理响应中的敏感数据，更新上下文
        ctx.setFinalResponse(inspectResponseText(authUser, ctx.getTraceId(), ctx.getAgentId(), ctx.getSessionId(),
            ctx.getFinalResponse()));
    }

    /**
     * 检测响应文本中的敏感数据
     * 
     * @param authUser  认证用户
     * @param traceId   追踪ID
     * @param agentId   Agent标识
     * @param sessionId 会话ID
     * @param text      响应文本
     * @return 脱敏后的文本
     */
    public String inspectResponseText(AuthUser authUser, String traceId, String agentId, String sessionId, String text) {
        return sensitiveDataService.inspectResponse(authUser, traceId, agentId, sessionId, text).text();
    }

    /**
     * 如果调用失败则触发告警
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
     * <p>将错误消息规范化后发送给AlertService。</p>
     * 
     * @param authUser    认证用户
     * @param ctx         管线上下文
     * @param errorMessage 错误消息
     */
    public void emitCallError(AuthUser authUser, PipelineContext ctx, String errorMessage) {
        alertService.emitCallError(authUser, ctx, normalizeErrorMessage(errorMessage));
    }

    /**
     * 记录成功的Token使用并触发相关告警
     * 
     * <p>核心流程：
     * <ol>
     *   <li>检查模型是否返回了有效的Token使用数据</li>
     *   <li>调用AiCallUsageService持久化到数据库</li>
     *   <li>将使用数据记录到追踪Span</li>
     * </ol>
     * </p>
     * 
     * @param span         追踪Span
     * @param operation    操作名称（如 echomind.chat.sync）
     * @param authUser     认证用户
     * @param ctx          管线上下文（包含Token使用数据）
     * @param startedNanos 调用开始时间（纳秒）
     * @return 持久化的使用记录实体，如果模型未返回有效数据则返回null
     */
    public AiCallUsageEntity recordSuccessAndWarnings(Span span, String operation, AuthUser authUser,
                                                      PipelineContext ctx, long startedNanos) {
        // 检查是否需要记录模型使用（某些场景可能不需要记录）
        if (modelUsageNotApplicable(ctx)) {
            return null;
        }

        // 记录成功的Token使用
        AiCallUsageEntity usage = usageService.recordSuccess(operation, authUser, ctx, startedNanos);
        
        // 将使用数据记录到追踪Span
        tagUsage(span, usage);
        
        return usage;
    }

    /**
     * 记录流式调用的Token使用
     * 
     * <p>处理流式聊天场景下的Token使用记录，包括成功和失败两种情况。</p>
     * 
     * @param span         追踪Span
     * @param operation    操作名称
     * @param authUser     认证用户
     * @param usageContext 管线上下文（包含Token使用数据）
     * @param startedNanos 调用开始时间（纳秒）
     * @param error        是否为错误场景
     * @param errorMessage 错误消息（仅error=true时有意义）
     */
    public void recordStreamUsage(Span span, String operation, AuthUser authUser, PipelineContext usageContext,
                                  long startedNanos, boolean error, String errorMessage) {
        // 非错误场景下检查是否需要记录
        if (!error && modelUsageNotApplicable(usageContext)) {
            return;
        }
        
        try {
            // 根据是否错误选择记录方法
            AiCallUsageEntity usage = error
                ? usageService.recordError(operation, authUser, usageContext, startedNanos,
                    normalizeErrorMessage(errorMessage))
                : usageService.recordSuccess(operation, authUser, usageContext, startedNanos);
            
            // 将使用数据记录到追踪Span
            tagUsage(span, usage);
            
            // 触发错误告警（如果有错误）
            if (error) {
                emitCallError(authUser, usageContext, errorMessage);
            } else {
                emitCallErrorIfFailed(authUser, usageContext);
            }
        } catch (TokenQuotaExceededException e) {
            // Token配额超限异常：记录到Span并重新抛出
            EchoMindTrace.recordException(span, e);
            throw e;
        } catch (RuntimeException e) {
            // 其他运行时异常：记录到Span，尝试触发告警，记录警告日志
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
     * @param ctx 管线上下文
     * @return true表示调用失败
     */
    public boolean isFailed(PipelineContext ctx) {
        return ctx != null && ctx.hasFailed();
    }

    /**
     * 获取错误消息
     * 
     * <p>优先级：失败原因 > 最终响应 > 默认消息</p>
     * 
     * @param ctx 管线上下文
     * @return 错误消息
     */
    public String errorMessage(PipelineContext ctx) {
        if (ctx == null) {
            return "模型调用失败";
        }
        if (ctx.hasFailed()) {
            return ctx.effectiveFailureReason();
        }
        if (ctx.getFinalResponse() == null || ctx.getFinalResponse().isBlank()) {
            return "模型调用失败";
        }
        return ctx.getFinalResponse();
    }

    /**
     * 检查用户Token配额
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
     * <p>设置以下Span属性：
     * <ul>
     *   <li>echomind.user_id - 用户ID</li>
     *   <li>echomind.account_type - 账户类型</li>
     *   <li>echomind.username - 用户名</li>
     *   <li>echomind.agent_id - Agent标识</li>
     *   <li>echomind.session_id - 会话ID</li>
     *   <li>echomind.model_id - 模型ID</li>
     *   <li>echomind.provider_id - Provider标识</li>
     *   <li>echomind.prompt_tokens - 提示词Token数</li>
     *   <li>echomind.completion_tokens - 回复Token数</li>
     *   <li>echomind.total_tokens - 总Token数</li>
     *   <li>echomind.usage_source - 使用来源</li>
     * </ul>
     * </p>
     * 
     * @param span  追踪Span
     * @param usage Token使用记录
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
     * <p>通过检查上下文中的特殊标记来判断。</p>
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
     * <p>去除前后空格，移除可能的[Error]前缀。</p>
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
     * @param value 输入值
     * @return 安全的字符串
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record RequestInspection(String governedMessage, String shortCircuitReply) {
        public static RequestInspection continueWith(String governedMessage) {
            return new RequestInspection(governedMessage, null);
        }

        public static RequestInspection shortCircuit(String reply) {
            return new RequestInspection(null, reply == null ? "" : reply);
        }

        public boolean shortCircuited() {
            return shortCircuitReply != null;
        }
    }
}
