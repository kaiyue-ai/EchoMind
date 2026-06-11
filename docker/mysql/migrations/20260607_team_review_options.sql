-- Team Run per-request review switches.
-- Defaults preserve the previous quality-first behavior.

SET @add_plan_review_enabled = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'echomind_agent_team_runs'
              AND column_name = 'plan_review_enabled'
        ),
        'SELECT 1',
        'ALTER TABLE echomind_agent_team_runs ADD COLUMN plan_review_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER task_level'
    )
);
PREPARE stmt FROM @add_plan_review_enabled;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_sub_review_enabled = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'echomind_agent_team_runs'
              AND column_name = 'sub_review_enabled'
        ),
        'SELECT 1',
        'ALTER TABLE echomind_agent_team_runs ADD COLUMN sub_review_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER plan_review_enabled'
    )
);
PREPARE stmt FROM @add_sub_review_enabled;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_global_review_enabled = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'echomind_agent_team_runs'
              AND column_name = 'global_review_enabled'
        ),
        'SELECT 1',
        'ALTER TABLE echomind_agent_team_runs ADD COLUMN global_review_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER sub_review_enabled'
    )
);
PREPARE stmt FROM @add_global_review_enabled;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_simple_fast_path_enabled = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'echomind_agent_team_runs'
              AND column_name = 'simple_fast_path_enabled'
        ),
        'SELECT 1',
        'ALTER TABLE echomind_agent_team_runs ADD COLUMN simple_fast_path_enabled TINYINT(1) NOT NULL DEFAULT 0 AFTER global_review_enabled'
    )
);
PREPARE stmt FROM @add_simple_fast_path_enabled;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
