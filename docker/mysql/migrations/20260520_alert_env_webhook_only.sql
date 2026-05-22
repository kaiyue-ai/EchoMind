SET @sql = (
    SELECT IF(
        COUNT(*) > 0,
        'ALTER TABLE echomind_alert_rules DROP COLUMN webhook_url',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'echomind_alert_rules'
      AND column_name = 'webhook_url'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
