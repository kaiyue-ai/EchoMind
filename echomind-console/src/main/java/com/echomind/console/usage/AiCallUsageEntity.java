package com.echomind.console.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 单次客户端模型调用审计记录。 */
@Entity
@Table(name = "echomind_ai_call_usage")
@Getter
@Setter
public class AiCallUsageEntity {

    @Id
    @Column(name = "id", length = 128)
    private String id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "username", length = 128)
    private String username;

    @Column(name = "account_type", nullable = false, length = 32)
    private String accountType = "client";

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "model_id", length = 255)
    private String modelId;

    @Column(name = "operation", nullable = false, length = 64)
    private String operation;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_source", nullable = false, length = 32)
    private TokenUsageSource usageSource = TokenUsageSource.PROVIDER;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
