CREATE TABLE IF NOT EXISTS echomind_token_quota_usage (
    user_id VARCHAR(128) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    bucket_start DATE NOT NULL,
    used_tokens BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, scope, bucket_start),
    INDEX idx_token_quota_usage_bucket (scope, bucket_start),
    CONSTRAINT chk_token_quota_usage_scope CHECK (scope IN ('daily', 'monthly'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
