SET @column_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'echomind_agent_team_runs'
      AND COLUMN_NAME = 'conflict_report_json'
);
SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE echomind_agent_team_runs ADD COLUMN conflict_report_json LONGTEXT AFTER global_review_json',
    'SELECT 1'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'echomind_agent_team_runs'
      AND COLUMN_NAME = 'arbitration_json'
);
SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE echomind_agent_team_runs ADD COLUMN arbitration_json LONGTEXT AFTER conflict_report_json',
    'SELECT 1'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'echomind_agent_team_runs'
      AND COLUMN_NAME = 'arbitration_count'
);
SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE echomind_agent_team_runs ADD COLUMN arbitration_count INT NOT NULL DEFAULT 0 AFTER full_replan_count',
    'SELECT 1'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
