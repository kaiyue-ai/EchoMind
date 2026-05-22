package com.echomind.console.sensitive;

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

@Entity
@Table(name = "echomind_sensitive_events")
@Getter
@Setter
public class SensitiveEventEntity {

    @Id
    @Column(name = "event_id", length = 128)
    private String eventId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "username", length = 128)
    private String username;

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "rule_id", length = 128)
    private String ruleId;

    @Column(name = "rule_name", length = 128)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 32)
    private SensitiveDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private SensitiveAction action;

    @Column(name = "match_count", nullable = false)
    private int matchCount;

    @Column(name = "sample", length = 1000)
    private String sample;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (eventId == null || eventId.isBlank()) {
            eventId = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
