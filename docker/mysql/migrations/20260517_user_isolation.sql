-- EchoMind 用户隔离第一阶段手工迁移脚本。
-- 适用场景：已有 MySQL 数据卷中 echomind_chat_sessions 仍使用单列 session_id 主键。
-- 新环境无需执行，docker/mysql/init.sql 会直接创建 user_id + session_id 复合主键。

USE echomind;

CREATE TABLE IF NOT EXISTS echomind_users (
    user_id VARCHAR(128) PRIMARY KEY,
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(512) NOT NULL,
    avatar_uri VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @schema_name = DATABASE();

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE echomind_users ADD COLUMN avatar_uri VARCHAR(512)',
        'SELECT ''echomind_users.avatar_uri exists'''
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'echomind_users'
      AND column_name = 'avatar_uri'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE echomind_chat_sessions ADD COLUMN user_id VARCHAR(128) NOT NULL DEFAULT ''default''',
        'SELECT ''echomind_chat_sessions.user_id exists'''
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'echomind_chat_sessions'
      AND column_name = 'user_id'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE echomind_chat_messages ADD COLUMN user_id VARCHAR(128) NOT NULL DEFAULT ''default''',
        'SELECT ''echomind_chat_messages.user_id exists'''
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'echomind_chat_messages'
      AND column_name = 'user_id'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_chat_session_user_activity ON echomind_chat_sessions(user_id, last_activity)',
        'SELECT ''idx_chat_session_user_activity exists'''
    )
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'echomind_chat_sessions'
      AND index_name = 'idx_chat_session_user_activity'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_chat_msg_user_session_time ON echomind_chat_messages(user_id, session_id, timestamp)',
        'SELECT ''idx_chat_msg_user_session_time exists'''
    )
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'echomind_chat_messages'
      AND index_name = 'idx_chat_msg_user_session_time'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @primary_columns = (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'echomind_chat_sessions'
      AND index_name = 'PRIMARY'
);

SET @sql = IF(
    @primary_columns = 'user_id,session_id',
    'SELECT ''echomind_chat_sessions primary key is current''',
    'ALTER TABLE echomind_chat_sessions DROP PRIMARY KEY, ADD PRIMARY KEY (user_id, session_id)'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
