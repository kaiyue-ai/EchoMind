CREATE TABLE IF NOT EXISTS echomind_provider_token_budget_usage (
    provider_id VARCHAR(128) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    bucket_start DATE NOT NULL,
    used_tokens BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (provider_id, scope, bucket_start),
    INDEX idx_provider_token_budget_usage_bucket (scope, bucket_start),
    CONSTRAINT chk_provider_token_budget_usage_scope CHECK (scope IN ('daily', 'weekly', 'monthly'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO echomind_provider_token_budget_usage
    (provider_id, scope, bucket_start, used_tokens, created_at, updated_at)
SELECT provider_id,
       'daily',
       bucket_start,
       SUM(total_tokens),
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6)
FROM (
    SELECT COALESCE(NULLIF(provider_id, ''), SUBSTRING_INDEX(model_id, ':', 1)) AS provider_id,
           DATE(created_at) AS bucket_start,
           total_tokens
    FROM echomind_ai_call_usage
    WHERE usage_source = 'PROVIDER'
      AND total_tokens > 0
) usage_rows
WHERE provider_id IS NOT NULL
  AND provider_id <> ''
GROUP BY provider_id, bucket_start
ON DUPLICATE KEY UPDATE
    used_tokens = VALUES(used_tokens),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO echomind_provider_token_budget_usage
    (provider_id, scope, bucket_start, used_tokens, created_at, updated_at)
SELECT provider_id,
       'weekly',
       bucket_start,
       SUM(total_tokens),
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6)
FROM (
    SELECT COALESCE(NULLIF(provider_id, ''), SUBSTRING_INDEX(model_id, ':', 1)) AS provider_id,
           DATE_SUB(DATE(created_at), INTERVAL WEEKDAY(created_at) DAY) AS bucket_start,
           total_tokens
    FROM echomind_ai_call_usage
    WHERE usage_source = 'PROVIDER'
      AND total_tokens > 0
) usage_rows
WHERE provider_id IS NOT NULL
  AND provider_id <> ''
GROUP BY provider_id, bucket_start
ON DUPLICATE KEY UPDATE
    used_tokens = VALUES(used_tokens),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO echomind_provider_token_budget_usage
    (provider_id, scope, bucket_start, used_tokens, created_at, updated_at)
SELECT provider_id,
       'monthly',
       bucket_start,
       SUM(total_tokens),
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6)
FROM (
    SELECT COALESCE(NULLIF(provider_id, ''), SUBSTRING_INDEX(model_id, ':', 1)) AS provider_id,
           CAST(DATE_FORMAT(created_at, '%Y-%m-01') AS DATE) AS bucket_start,
           total_tokens
    FROM echomind_ai_call_usage
    WHERE usage_source = 'PROVIDER'
      AND total_tokens > 0
) usage_rows
WHERE provider_id IS NOT NULL
  AND provider_id <> ''
GROUP BY provider_id, bucket_start
ON DUPLICATE KEY UPDATE
    used_tokens = VALUES(used_tokens),
    updated_at = CURRENT_TIMESTAMP(6);
