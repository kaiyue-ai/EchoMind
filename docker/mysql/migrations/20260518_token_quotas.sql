CREATE TABLE IF NOT EXISTS echomind_token_quotas (
    user_id VARCHAR(128) PRIMARY KEY,
    daily_limit_tokens BIGINT,
    monthly_limit_tokens BIGINT,
    warning_threshold_percent INT NOT NULL DEFAULT 80,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_token_quotas_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
