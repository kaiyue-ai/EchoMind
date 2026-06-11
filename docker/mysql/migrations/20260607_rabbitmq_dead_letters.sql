-- RabbitMQ dead-letter audit and controlled replay records.

CREATE TABLE IF NOT EXISTS echomind_rabbitmq_dead_letters (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_hash VARCHAR(64) NOT NULL,
    dlq_name VARCHAR(255) NOT NULL,
    message_type VARCHAR(64) NOT NULL,
    business_key VARCHAR(255) NULL,
    trace_id VARCHAR(64) NULL,
    payload_json LONGTEXT NOT NULL,
    error_headers_json LONGTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ARCHIVED',
    replay_count INT NOT NULL DEFAULT 0,
    last_replay_error VARCHAR(1000) NULL,
    archived_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    replayed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_rabbitmq_dead_letter_hash (message_hash),
    INDEX idx_rabbitmq_dead_letter_status_time (status, archived_at),
    INDEX idx_rabbitmq_dead_letter_type_time (message_type, archived_at),
    INDEX idx_rabbitmq_dead_letter_business_key (business_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
