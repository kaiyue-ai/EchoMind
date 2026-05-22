DELETE FROM echomind_agent_team_events;
DELETE FROM echomind_agent_team_steps;
DELETE FROM echomind_agent_team_runs;
DELETE FROM echomind_agent_team_members;
DELETE FROM echomind_agent_teams;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_runs' AND COLUMN_NAME = 'user_id'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_runs ADD COLUMN user_id VARCHAR(128) NOT NULL DEFAULT ''default'' AFTER team_id',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_runs' AND COLUMN_NAME = 'task_level'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_runs ADD COLUMN task_level VARCHAR(32) NOT NULL DEFAULT ''COMPLEX'' AFTER status',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_runs' AND COLUMN_NAME = 'merge_output'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_runs ADD COLUMN merge_output LONGTEXT AFTER result_review_json',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_runs' AND COLUMN_NAME = 'global_review_json'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_runs ADD COLUMN global_review_json LONGTEXT AFTER merge_output',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_runs' AND COLUMN_NAME = 'partial_replan_count'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_runs ADD COLUMN partial_replan_count INT NOT NULL DEFAULT 0 AFTER result_replan_count',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_runs' AND COLUMN_NAME = 'full_replan_count'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_runs ADD COLUMN full_replan_count INT NOT NULL DEFAULT 0 AFTER partial_replan_count',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_runs' AND INDEX_NAME = 'idx_team_run_user'
);
SET @ddl := IF(@index_exists = 0,
    'CREATE INDEX idx_team_run_user ON echomind_agent_team_runs (user_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_steps' AND COLUMN_NAME = 'client_step_id'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_steps ADD COLUMN client_step_id VARCHAR(128) AFTER step_index',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_steps' AND COLUMN_NAME = 'depends_on_step_ids_json'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_steps ADD COLUMN depends_on_step_ids_json LONGTEXT AFTER required_capabilities_json',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_steps' AND COLUMN_NAME = 'risk_level'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_steps ADD COLUMN risk_level VARCHAR(32) NOT NULL DEFAULT ''LOW'' AFTER depends_on_step_ids_json',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_steps' AND COLUMN_NAME = 'risk_reason'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_steps ADD COLUMN risk_reason LONGTEXT AFTER risk_level',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_steps' AND COLUMN_NAME = 'quality_status'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_steps ADD COLUMN quality_status VARCHAR(32) NOT NULL DEFAULT ''PENDING'' AFTER review_status',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_steps' AND COLUMN_NAME = 'sub_review_json'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_steps ADD COLUMN sub_review_json LONGTEXT AFTER quality_status',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_steps' AND COLUMN_NAME = 'last_review_reason'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_steps ADD COLUMN last_review_reason LONGTEXT AFTER sub_review_json',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_steps' AND COLUMN_NAME = 'reflection_json'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_steps ADD COLUMN reflection_json LONGTEXT AFTER last_review_reason',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'echomind_agent_team_steps' AND COLUMN_NAME = 'plan_iteration'
);
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE echomind_agent_team_steps ADD COLUMN plan_iteration INT NOT NULL DEFAULT 0 AFTER reflection_json',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
