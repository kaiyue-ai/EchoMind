package com.echomind.console.alerts;

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
@Table(name = "echomind_alert_events")
@Getter
@Setter
public class AlertEventEntity {

    @Id
    @Column(name = "event_id", length = 128)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 64)
    private AlertType alertType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 32)
    private AlertSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AlertStatus status;

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

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "message", length = 2000)
    private String message;

    @Column(name = "suggestion", length = 1000)
    private String suggestion;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "escalated", nullable = false)
    private boolean escalated = false;

    @Column(name = "suppressed_count", nullable = false)
    private int suppressedCount = 0;

    @Column(name = "provider_response", length = 1000)
    private String providerResponse;

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
