-- Remove obsolete Team clarification flow state.
-- Created: 2026-06-12

UPDATE echomind_agent_team_runs
SET status = 'FAILED',
    final_output = COALESCE(
        NULLIF(final_output, ''),
        'Run 停在已下线的澄清状态，已自动标记失败。请重新发起 Team 任务。'
    )
WHERE status = 'NEEDS_CLARIFICATION';

UPDATE echomind_agent_team_events
SET type = 'RUN_FAILED',
    message = COALESCE(
        NULLIF(message, ''),
        '历史澄清请求事件已随澄清流程下线转为失败记录'
    )
WHERE type = 'CLARIFICATION_REQUESTED';

UPDATE echomind_agent_team_events
SET type = 'RUN_CREATED',
    message = COALESCE(NULLIF(message, ''), '历史 Run 恢复事件已随澄清流程下线归档')
WHERE type = 'RUN_RESUMED';

SET @drop_team_run_clarification_question = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'echomind_agent_team_runs'
              AND column_name = 'clarification_question'
        ),
        'ALTER TABLE echomind_agent_team_runs DROP COLUMN clarification_question',
        'SELECT 1'
    )
);
PREPARE stmt FROM @drop_team_run_clarification_question;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @drop_team_run_clarification_answer = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'echomind_agent_team_runs'
              AND column_name = 'clarification_answer'
        ),
        'ALTER TABLE echomind_agent_team_runs DROP COLUMN clarification_answer',
        'SELECT 1'
    )
);
PREPARE stmt FROM @drop_team_run_clarification_answer;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @drop_team_run_clarification_stage = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'echomind_agent_team_runs'
              AND column_name = 'clarification_stage'
        ),
        'ALTER TABLE echomind_agent_team_runs DROP COLUMN clarification_stage',
        'SELECT 1'
    )
);
PREPARE stmt FROM @drop_team_run_clarification_stage;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
