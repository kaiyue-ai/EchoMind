package com.echomind.console.alerts;

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
import java.util.UUID;

@Entity
@Table(name = "echomind_alert_rules")
@Getter
@Setter
public class AlertRuleEntity {

    @Id
    @Column(name = "rule_id", length = 128)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 64)
    private AlertType alertType;

    @Column(name = "rule_name", nullable = false, length = 128)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 32)
    private AlertSeverity severity = AlertSeverity.WARNING;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "threshold_percent")
    private Double thresholdPercent;

    @Column(name = "window_minutes")
    private Integer windowMinutes;

    @Column(name = "quiet_minutes", nullable = false)
    private int quietMinutes = 30;

    @Column(name = "escalation_enabled", nullable = false)
    private boolean escalationEnabled = true;

    @Column(name = "escalation_threshold", nullable = false)
    private int escalationThreshold = 3;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (ruleId == null || ruleId.isBlank()) {
            ruleId = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
