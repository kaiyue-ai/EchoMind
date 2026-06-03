-- 回滚：移除 Team Run 表的无效 version 字段
-- 创建时间: 2026-05-30

SET @drop_idx_team_runs_status_version = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'echomind_agent_team_runs'
              AND index_name = 'idx_team_runs_status_version'
        ),
        'ALTER TABLE echomind_agent_team_runs DROP INDEX idx_team_runs_status_version',
        'SELECT 1'
    )
);
PREPARE stmt FROM @drop_idx_team_runs_status_version;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @drop_team_run_version_column = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'echomind_agent_team_runs'
              AND column_name = 'version'
        ),
        'ALTER TABLE echomind_agent_team_runs DROP COLUMN version',
        'SELECT 1'
    )
);
PREPARE stmt FROM @drop_team_run_version_column;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 清理已废弃的 SIMPLE_DRAFT 事件类型
SET @delete_simple_draft_events = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'echomind_agent_team_events'
              AND column_name = 'event_type'
        ),
        'DELETE FROM echomind_agent_team_events WHERE event_type IN (''SIMPLE_DRAFT_STARTED'', ''SIMPLE_DRAFT_COMPLETED'')',
        'SELECT 1'
    )
);
PREPARE stmt FROM @delete_simple_draft_events;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
