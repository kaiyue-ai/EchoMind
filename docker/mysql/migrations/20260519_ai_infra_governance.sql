CREATE TABLE IF NOT EXISTS echomind_sensitive_rules (
    rule_id VARCHAR(128) PRIMARY KEY,
    rule_name VARCHAR(128) NOT NULL,
    pattern VARCHAR(1000) NOT NULL,
    replacement VARCHAR(128) NOT NULL,
    action VARCHAR(32) NOT NULL DEFAULT 'MASK',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_sensitive_rules_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS echomind_sensitive_events (
    event_id VARCHAR(128) PRIMARY KEY,
    trace_id VARCHAR(64),
    user_id VARCHAR(128),
    username VARCHAR(128),
    agent_id VARCHAR(128),
    session_id VARCHAR(128),
    rule_id VARCHAR(128),
    rule_name VARCHAR(128),
    direction VARCHAR(32) NOT NULL,
    action VARCHAR(32) NOT NULL,
    match_count INT NOT NULL DEFAULT 0,
    sample VARCHAR(1000),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_sensitive_events_time (created_at),
    INDEX idx_sensitive_events_trace (trace_id),
    INDEX idx_sensitive_events_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS echomind_alert_rules (
    rule_id VARCHAR(128) PRIMARY KEY,
    alert_type VARCHAR(64) NOT NULL,
    rule_name VARCHAR(128) NOT NULL,
    severity VARCHAR(32) NOT NULL DEFAULT 'WARNING',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    threshold_percent DOUBLE,
    window_minutes INT,
    quiet_minutes INT NOT NULL DEFAULT 30,
    webhook_url VARCHAR(1000),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alert_rules_type (alert_type),
    INDEX idx_alert_rules_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS echomind_alert_events (
    event_id VARCHAR(128) PRIMARY KEY,
    alert_type VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trace_id VARCHAR(64),
    user_id VARCHAR(128),
    username VARCHAR(128),
    agent_id VARCHAR(128),
    session_id VARCHAR(128),
    title VARCHAR(255) NOT NULL,
    message VARCHAR(2000),
    suggestion VARCHAR(1000),
    failure_reason VARCHAR(1000),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_alert_events_time (created_at),
    INDEX idx_alert_events_type_time (alert_type, created_at),
    INDEX idx_alert_events_trace (trace_id),
    INDEX idx_alert_events_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
