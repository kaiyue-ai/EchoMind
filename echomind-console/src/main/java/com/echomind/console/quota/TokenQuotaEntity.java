package com.echomind.console.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "echomind_token_quotas")
@Getter
@Setter
public class TokenQuotaEntity {

    @Id
    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "daily_limit_tokens")
    private Long dailyLimitTokens;

    @Column(name = "monthly_limit_tokens")
    private Long monthlyLimitTokens;

    @Column(name = "warning_threshold_percent", nullable = false)
    private int warningThresholdPercent = 80;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TokenQuotaStatus status = TokenQuotaStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
