SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE echomind_alert_rules ADD COLUMN escalation_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER webhook_url',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'echomind_alert_rules'
      AND column_name = 'escalation_enabled'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE echomind_alert_rules ADD COLUMN escalation_threshold INT NOT NULL DEFAULT 3 AFTER escalation_enabled',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'echomind_alert_rules'
      AND column_name = 'escalation_threshold'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE echomind_alert_events ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE AFTER failure_reason',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'echomind_alert_events'
      AND column_name = 'escalated'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE echomind_alert_events ADD COLUMN suppressed_count INT NOT NULL DEFAULT 0 AFTER escalated',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'echomind_alert_events'
      AND column_name = 'suppressed_count'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE echomind_alert_events ADD COLUMN provider_response VARCHAR(1000) AFTER suppressed_count',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'echomind_alert_events'
      AND column_name = 'provider_response'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
