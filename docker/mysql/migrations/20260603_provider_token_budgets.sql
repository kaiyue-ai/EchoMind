SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE echomind_ai_call_usage ADD COLUMN provider_id VARCHAR(128) AFTER model_id',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'echomind_ai_call_usage'
      AND column_name = 'provider_id'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_ai_usage_provider_time ON echomind_ai_call_usage (provider_id, created_at)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'echomind_ai_call_usage'
      AND index_name = 'idx_ai_usage_provider_time'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS echomind_provider_token_budgets (
    provider_id VARCHAR(128) PRIMARY KEY,
    daily_limit_tokens BIGINT,
    weekly_limit_tokens BIGINT,
    monthly_limit_tokens BIGINT,
    warning_threshold_percent INT NOT NULL DEFAULT 80,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_provider_token_budgets_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE echomind_alert_events ADD COLUMN provider_id VARCHAR(128) AFTER session_id',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'echomind_alert_events'
      AND column_name = 'provider_id'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_alert_events_provider_time ON echomind_alert_events (provider_id, created_at)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'echomind_alert_events'
      AND index_name = 'idx_alert_events_provider_time'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
