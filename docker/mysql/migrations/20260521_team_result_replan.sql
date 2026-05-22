SET @column_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'echomind_agent_team_runs'
      AND COLUMN_NAME = 'result_replan_count'
);

SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE echomind_agent_team_runs ADD COLUMN result_replan_count INT NOT NULL DEFAULT 0 AFTER plan_retry_count',
    'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
