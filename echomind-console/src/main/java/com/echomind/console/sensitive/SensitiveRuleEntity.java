package com.echomind.console.sensitive;

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
@Table(name = "echomind_sensitive_rules")
@Getter
@Setter
public class SensitiveRuleEntity {

    @Id
    @Column(name = "rule_id", length = 128)
    private String ruleId;

    @Column(name = "rule_name", nullable = false, length = 128)
    private String ruleName;

    @Column(name = "pattern", nullable = false, length = 1000)
    private String pattern;

    @Column(name = "replacement", nullable = false, length = 128)
    private String replacement;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private SensitiveAction action = SensitiveAction.MASK;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

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
