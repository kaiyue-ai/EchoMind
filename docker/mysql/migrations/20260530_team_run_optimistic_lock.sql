-- 回滚：移除 Team Run 表的无效 version 字段
-- 创建时间: 2026-05-30

DROP INDEX idx_team_runs_status_version ON echomind_agent_team_runs;

ALTER TABLE echomind_agent_team_runs
DROP COLUMN version;

-- 清理已废弃的 SIMPLE_DRAFT 事件类型
DELETE FROM echomind_agent_team_events
WHERE event_type IN ('SIMPLE_DRAFT_STARTED', 'SIMPLE_DRAFT_COMPLETED');
