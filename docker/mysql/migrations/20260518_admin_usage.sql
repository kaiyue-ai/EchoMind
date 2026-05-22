-- 项目三管理端认证和客户端 AI 调用审计。

CREATE TABLE IF NOT EXISTS echomind_admin_users (
    admin_id VARCHAR(128) PRIMARY KEY,
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS echomind_ai_call_usage (
    id VARCHAR(128) PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128),
    user_id VARCHAR(128) NOT NULL,
    username VARCHAR(128),
    account_type VARCHAR(32) NOT NULL DEFAULT 'client',
    agent_id VARCHAR(128),
    session_id VARCHAR(128),
    model_id VARCHAR(255),
    operation VARCHAR(64) NOT NULL,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    usage_source VARCHAR(32) NOT NULL DEFAULT 'PROVIDER',
    duration_ms BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    error_message VARCHAR(1000),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_ai_usage_user_time (user_id, created_at),
    INDEX idx_ai_usage_trace (trace_id),
    INDEX idx_ai_usage_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
