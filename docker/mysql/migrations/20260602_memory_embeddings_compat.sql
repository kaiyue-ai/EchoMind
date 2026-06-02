-- Keep legacy memory embedding cleanup repository bootable on upgraded databases.
-- New ordinary chat memory no longer writes this table, but existing admin cleanup
-- code still maps it for deleting old vector backup rows.

CREATE TABLE IF NOT EXISTS echomind_memory_embeddings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(128) NOT NULL,
    message_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content_preview VARCHAR(1000),
    embedding_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_memory_embedding_session (session_id),
    INDEX idx_memory_embedding_message (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
