-- Scope Team definitions to the current client user. Existing global teams become default-owned.

SET @add_team_owner_column = (
    SELECT IF(
        NOT EXISTS (
            SELECT 1
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'echomind_agent_teams'
              AND COLUMN_NAME = 'owner_user_id'
        ),
        'ALTER TABLE echomind_agent_teams ADD COLUMN owner_user_id VARCHAR(128) NOT NULL DEFAULT ''default'' AFTER team_id',
        'SELECT ''echomind_agent_teams.owner_user_id exists'''
    )
);
PREPARE stmt FROM @add_team_owner_column;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE echomind_agent_teams
SET owner_user_id = 'default'
WHERE owner_user_id IS NULL OR owner_user_id = '';

SET @add_team_owner_index = (
    SELECT IF(
        NOT EXISTS (
            SELECT 1
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'echomind_agent_teams'
              AND INDEX_NAME = 'idx_agent_team_owner_time'
        ),
        'CREATE INDEX idx_agent_team_owner_time ON echomind_agent_teams (owner_user_id, created_at)',
        'SELECT ''echomind_agent_teams.idx_agent_team_owner_time exists'''
    )
);
PREPARE stmt FROM @add_team_owner_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
