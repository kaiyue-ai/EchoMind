SET @sql = (
    SELECT IF(
        COUNT(*) > 0,
        CONCAT('ALTER TABLE echomind_token_quotas DROP COLUMN ', 'warning_', 'threshold_', 'percent'),
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'echomind_token_quotas'
      AND column_name = CONCAT('warning_', 'threshold_', 'percent')
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
