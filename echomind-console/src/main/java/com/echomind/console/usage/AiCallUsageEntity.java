package com.echomind.console.usage;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 单次客户端模型调用审计记录。 */
@TableName("echomind_ai_call_usage")
@Getter
@Setter
public class AiCallUsageEntity {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("trace_id")
    private String traceId;

    @TableField("request_id")
    private String requestId;

    @TableField("user_id")
    private String userId;

    private String username;

    @TableField("account_type")
    private String accountType = "client";

    @TableField("agent_id")
    private String agentId;

    @TableField("session_id")
    private String sessionId;

    @TableField("model_id")
    private String modelId;

    @TableField("provider_id")
    private String providerId;

    private String operation;

    @TableField("prompt_tokens")
    private long promptTokens;

    @TableField("completion_tokens")
    private long completionTokens;

    @TableField("total_tokens")
    private long totalTokens;

    @TableField("usage_source")
    private TokenUsageSource usageSource = TokenUsageSource.PROVIDER;

    @TableField("duration_ms")
    private long durationMs;

    private String status;

    @TableField("error_message")
    private String errorMessage;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
